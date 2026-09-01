/*
 * hud_renderer.cpp — 防捕获 HUD 的状态存储与绘制。
 *
 * 绘制元素与旧 OverlayCanvasView(兜底渲染)的视觉参数逐项对齐:
 *   FOV 圈      2px 描边,白 0xAA 透明度,半径动态
 *   四角框      4px 白不透明线,角长 36px,离角点 10px 起笔
 *   中心点      4px 半径实心圆,白 0xAA
 *   检测框      2px 描边,白 0x99
 *   推理信息    顶部居中 blit Kotlin 传来的 ARGB 位图
 *
 * 坐标系:真机 dumpsys SurfaceFlinger 实证(RMX3366 / A13,横屏时
 * layerStackSpace=2400x1080 而 framebufferSpace=1080x2400+ROTATION_90):
 * layer 生活在 layer-stack 逻辑空间,该空间**跟随屏幕旋转**,旋转发生在
 * layer-stack → framebuffer 之间。而 MediaProjection 采集帧也是逻辑
 * 方向 —— 所以采集坐标 == layer 坐标,恒等映射,无需旋转变换。
 *
 * 性能设计(真机教训):全屏 layer 按推理帧率整屏重写会让游戏明显卡顿
 * —— 每帧 2400x1080x4B≈10MB 的清屏写入 + 提交,~40fps 就是 400MB/s
 * 内存写流量,和游戏抢带宽;且三缓冲下不能"只清脏区"(buffer 轮换会带
 * 出 3 帧前的残影)。因此:
 *   1. 几何内容只占屏幕中心一块 → layer 缩成中心方块(面积 ~1/4)
 *   2. 推理信息是顶部小条且只 2Hz 更新 → 独立小 layer(~960x120)
 *   3. 全部开关关闭 → 干脆销毁 layer,连静态合成开销都不留
 *   4. 渲染线程降优先级,绝不和游戏抢大核
 */

#include "hud_renderer.h"
#include "hud_surface.h"

#include <android/log.h>
#include <android/native_window.h>

#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <sys/resource.h>
#include <thread>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define ALOG(level, fmt, ...) \
    __android_log_print(level, "YolovaimHUD", fmt, ##__VA_ARGS__)

namespace hud {

static const int MAX_BOXES = 16;

// 几何 layer 相对内容包围盒的固定余量:覆盖"目标中心在截取圈内、
// 检测框边缘越出圈外"的常见情况
static const int RANGE_MARGIN = 320;

struct HudState {
    bool showFov = false;
    bool showRange = false;
    bool showBox = false;
    bool showDot = false;
    bool showInfo = false;

    // 自检模式:无视正常开关,只画洋红色检查图案(中心实心圆 + 大圆环)。
    // 用游戏画面不可能出现的颜色做判据,避免与游戏自身 UI(比如屏幕
    // 中心的准星)撞车 —— 首版用"白像素"判据就栽在这上面。
    // 自检图案覆盖大半个屏,强制几何 layer 临时扩为全屏。
    bool checkMode = false;

    int geoW = 0; // 采集空间宽高(MediaProjection 坐标系,= layer-stack 空间)
    int geoH = 0;

    int fovRadius = 50;
    int rangeRadius = 300;

    int nBoxes = 0;
    int boxes[MAX_BOXES][4] = {}; // x1 y1 x2 y2,采集空间

    bool textValid = false;
    int textW = 0;
    int textH = 0;
    std::vector<uint32_t> text; // Android 0xAARRGGBB
};

static std::mutex g_mutex;
static std::condition_variable g_cv;
static bool g_running = false; // 渲染线程存活
static bool g_started = false; // renderer_start 成功过
static bool g_dirty = false;
static std::thread g_render_thread;
static HudState g_state;

// 文字位图的待写入缓冲(begin→run*→end 期间 accumulate,不参与渲染)
static std::vector<uint32_t> g_text_pending;
static int g_text_pending_w = 0;
static int g_text_pending_h = 0;
static bool g_text_in_begin = false;

// =========================================================================
// 绘制
// =========================================================================

struct DrawCtx {
    ANativeWindow_Buffer *buf;
    int gw, gh;   // 采集空间尺寸(== layer-stack 尺寸,恒等映射)
    int ox, oy;   // 当前 layer 左上角在采集空间的位置(元素画进小 layer 的偏移)
};

// 采集空间 → layer 内部像素:恒等映射 + 平移到 layer 原点。
// 旋转结论见文件头:layer-stack 空间跟随屏幕旋转,与采集坐标一致。
static inline void plot(const DrawCtx &c, int x, int y, uint32_t rgba) {
    if (x < 0 || y < 0 || x >= c.gw || y >= c.gh)
        return;
    int lx = x - c.ox;
    int ly = y - c.oy;
    if (lx < 0 || ly < 0 || lx >= c.buf->width || ly >= c.buf->height)
        return;
    uint32_t *row = reinterpret_cast<uint32_t *>(c.buf->bits) +
                    static_cast<size_t>(ly) * c.buf->stride;
    row[lx] = rgba;
}

// ARGB(0xAARRGGBB)→ 内存小端 RGBA(0xAABBGGRR):
// A、G 字节位置不动,R 和 B 交换。真机抓过一次反例:写成字节旋转
// (整体移 8 位)会把黑底 0xCC101010 变成粉底、黄字 0xFFFFEB3B 变成
// 白字 —— 推理信息条在物理屏上就是从那时开始颜色全错的。
static inline uint32_t argb_to_rgba(uint32_t v) {
    return (v & 0xFF00FF00u) | ((v >> 16) & 0x000000FFu) | ((v & 0x000000FFu) << 16);
}

static void hline(const DrawCtx &c, int x1, int x2, int y, int thickness, uint32_t rgba) {
    if (x1 > x2) { int t = x1; x1 = x2; x2 = t; }
    for (int dy = 0; dy < thickness; dy++)
        for (int x = x1; x <= x2; x++)
            plot(c, x, y + dy, rgba);
}

static void vline(const DrawCtx &c, int x, int y1, int y2, int thickness, uint32_t rgba) {
    if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
    for (int dx = 0; dx < thickness; dx++)
        for (int y = y1; y <= y2; y++)
            plot(c, x + dx, y, rgba);
}

// 参数化圆环:比包围盒逐像素判定快一个数量级(动态 FOV 动画期间
// fovRadius 每帧都变,这里在热路径上)。
static void circle(const DrawCtx &c, float cx, float cy, float r, int thickness, uint32_t rgba) {
    if (r <= 0.f)
        return;
    int steps = static_cast<int>(r * 6.f);
    if (steps < 90) steps = 90;
    if (steps > 4000) steps = 4000;
    for (int i = 0; i < steps; i++) {
        float a = 2.f * static_cast<float>(M_PI) * i / steps;
        for (int k = 0; k < thickness; k++) {
            float rr = r + k - (thickness - 1) * 0.5f;
            plot(c, static_cast<int>(cx + cosf(a) * rr),
                      static_cast<int>(cy + sinf(a) * rr), rgba);
        }
    }
}

static void fill_circle(const DrawCtx &c, int cx, int cy, int r, uint32_t rgba) {
    for (int y = cy - r; y <= cy + r; y++)
        for (int x = cx - r; x <= cx + r; x++) {
            int dx = x - cx, dy = y - cy;
            if (dx * dx + dy * dy <= r * r)
                plot(c, x, y, rgba);
        }
}

// 四角括号框 —— 逐行复刻 OverlayCanvasView.drawCornerBox 的几何
// (角长 36,起笔离角点 10,4px 线,小框直接不画)。
static void corner_box(const DrawCtx &c, int l, int t, int r, int b, uint32_t rgba) {
    const int len = 36;
    const int cr = 10;
    const int th = 4;
    if (r - l < len * 2 + cr * 2 || b - t < len * 2 + cr * 2)
        return;
    vline(c, l, t + cr, t + len, th, rgba);
    hline(c, l + cr, l + len, t, th, rgba);
    hline(c, r - len, r - cr, t, th, rgba);
    vline(c, r, t + cr, t + len, th, rgba);
    vline(c, l, b - len, b - cr, th, rgba);
    hline(c, l + cr, l + len, b, th, rgba);
    hline(c, r - len, r - cr, b, th, rgba);
    vline(c, r, b - len, b - cr, th, rgba);
}

// 文字位图 blit 到采集空间 (x0,y0)(由 plot 平移进文字 layer)。
static void blit_text(const DrawCtx &c, const HudState &s, int x0, int y0) {
    if (!s.textValid || s.textW <= 0 || s.textH <= 0)
        return;
    for (int y = 0; y < s.textH; y++) {
        const uint32_t *src = s.text.data() + static_cast<size_t>(y) * s.textW;
        for (int x = 0; x < s.textW; x++) {
            if ((src[x] & 0xFF000000u) != 0)
                plot(c, x0 + x, y0 + y, argb_to_rgba(src[x]));
        }
    }
}

// =========================================================================
// layer 管理(渲染线程私有,无需加锁)
// =========================================================================

static HudSurface g_geo;  // 几何内容方块(中心)
static HudSurface g_text; // 推理信息文字条(顶部)
static int g_geo_created_gw = 0, g_geo_created_gh = 0; // 几何 layer 创建时的采集几何
static int g_text_created_gw = 0;                      // 文字 layer 创建时的采集几何
static bool g_check_fullscreen = false;                // 当前几何 layer 是否为自检扩成全屏

// 几何 layer 的期望尺寸:包住所有可能画的东西;超出当前 layer 时长大,
// 大幅缩小时收编(滞回,防检测框抖动导致重建抖动)。
static void desired_geo_dims(const HudState &s, int *w, int *h) {
    if (s.checkMode) { // 自检图案是 min/4 半径的大圆环,直接要全屏
        *w = s.geoW;
        *h = s.geoH;
        return;
    }
    const int cx = s.geoW / 2, cy = s.geoH / 2;
    int hw = 32, hh = 32; // 中心到 layer 边的半宽/半高
    if (s.showRange) {
        hw = s.rangeRadius + RANGE_MARGIN;
        hh = s.rangeRadius + RANGE_MARGIN;
    }
    if (s.showFov && s.fovRadius + 16 > hw) {
        hw = s.fovRadius + 16;
        hh = s.fovRadius + 16;
    }
    if (s.showBox) {
        for (int i = 0; i < s.nBoxes; i++) {
            const int *b = s.boxes[i];
            int bx = b[0] < b[2] ? b[2] : b[0]; // 离中心更远的边
            int by = b[1] < b[3] ? b[3] : b[1];
            int ex = bx - cx;
            if (ex < 0) ex = -ex;
            int ey = by - cy;
            if (ey < 0) ey = -ey;
            if (ex + 2 > hw) hw = ex + 2;
            if (ey + 2 > hh) hh = ey + 2;
        }
    }
    *w = s.geoW < hw * 2 ? s.geoW : hw * 2;
    *h = s.geoH < hh * 2 ? s.geoH : hh * 2;
}

// 清屏 + 回调绘制 + 提交一个 layer。draw_ctx 已含该 layer 的原点。
static bool present_layer(HudSurface *s, const DrawCtx &ctx, void (*draw_fn)(const DrawCtx &, const HudState &), const HudState &st) {
    ANativeWindow_Buffer buf{};
    if (0 != ANativeWindow_lock(s->win, &buf, nullptr)) {
        ALOG(ANDROID_LOG_ERROR, "render: lock failed (%dx%d)", s->w, s->h);
        return false;
    }
    // 清屏:格式带 alpha 时全透明,否则退化为黑底(实测要点,方案 §3.3)。
    // layer 已经缩到内容大小,这里的全清是几 MB 而不是 10MB。
    uint32_t bg = (buf.format == 1 /* WINDOW_FORMAT_RGBA_8888 */) ? 0u : 0xFF000000u;
    uint32_t *bits = reinterpret_cast<uint32_t *>(buf.bits);
    for (int y = 0; y < buf.height; ++y) {
        uint32_t *row = bits + static_cast<size_t>(y) * buf.stride;
        for (int x = 0; x < buf.width; ++x)
            row[x] = bg;
    }
    DrawCtx local = ctx;
    local.buf = &buf;
    if (draw_fn)
        draw_fn(local, st);
    if (0 != ANativeWindow_unlockAndPost(s->win)) {
        ALOG(ANDROID_LOG_ERROR, "render: unlockAndPost failed (%dx%d)", s->w, s->h);
        return false;
    }
    return true;
}

// ---- 两个 layer 各自的绘制函数 ----

static void draw_geometry(const DrawCtx &ctx, const HudState &s) {
    const int cx = s.geoW / 2;
    const int cy = s.geoH / 2;

    // 颜色对齐 OverlayCanvasView 的 Paint:
    // corner=不透明白,fov/dot=0xAA 白,box=0x99 白(灰阶+对称
    // alpha 下 RGBA 与 ARGB 数值恰好相同,直接写)
    const uint32_t colCorner = 0xFFFFFFFFu;
    const uint32_t colFov = 0xAAFFFFFFu;
    const uint32_t colDot = 0xAAFFFFFFu;
    const uint32_t colBox = 0x99FFFFFFu;

    if (s.checkMode) {
        // 自检图案:洋红(0xFFFF00FF,ARGB/RGBA 数值相同),游戏画面
        // 不会天然出现;中心 r=12 实心圆 + 半屏 1/4 圆环,采样容易
        const uint32_t magenta = 0xFFFF00FFu;
        fill_circle(ctx, cx, cy, 12, magenta);
        int r = (s.geoW < s.geoH ? s.geoW : s.geoH) / 4;
        circle(ctx, cx, cy, static_cast<float>(r), 4, magenta);
        return;
    }

    // 中心点是独立开关(顺手修掉旧实现嵌在 showCaptureRange
    // if 里的坑,方案 §5.3)
    if (s.showDot)
        fill_circle(ctx, cx, cy, 4, colDot);

    if (s.showRange) {
        int half = s.rangeRadius;
        corner_box(ctx, cx - half, cy - half, cx + half, cy + half, colCorner);
    }

    if (s.showFov && s.fovRadius > 0)
        circle(ctx, cx, cy, static_cast<float>(s.fovRadius), 2, colFov);

    if (s.showBox) {
        for (int i = 0; i < s.nBoxes; i++) {
            const int *b = s.boxes[i];
            const int th = 2;
            hline(ctx, b[0], b[2], b[1], th, colBox);
            hline(ctx, b[0], b[2], b[3], th, colBox);
            vline(ctx, b[0], b[1], b[3], th, colBox);
            vline(ctx, b[2], b[1], b[3], th, colBox);
        }
    }
}

static void draw_text(const DrawCtx &ctx, const HudState &s) {
    // 顶部居中,与旧 TextView(dp(12)+状态栏)的落点对齐
    blit_text(ctx, s, (s.geoW - s.textW) / 2, 40);
}

static void render(const HudState &s) {
    if (s.geoW <= 0 || s.geoH <= 0)
        return;

    const int cx = s.geoW / 2, cy = s.geoH / 2;
    const bool geoContent = s.checkMode || s.showFov || s.showRange || s.showBox || s.showDot;

    if (geoContent) {
        int w, h;
        desired_geo_dims(s, &w, &h);
        // 重建判定:不存在 / 采集几何变了(旋转) / 内容超出 / 大幅缩小 / 自检切换
        bool need = !g_geo.win ||
                    g_geo_created_gw != s.geoW || g_geo_created_gh != s.geoH ||
                    w > g_geo.w || h > g_geo.h ||
                    (w * 3 < g_geo.w * 2 && h * 3 < g_geo.h * 2) ||
                    g_check_fullscreen != s.checkMode;
        if (need) {
            surface_destroy(&g_geo);
            // 长大时多留 128px 余量:检测框在尺寸边界附近抖动时,
            // 避免每帧"超一点→重建"的反复重建闪烁
            int cw = w, ch = h;
            if (!s.checkMode) {
                if (cw < s.geoW) cw += 128;
                if (ch < s.geoH) ch += 128;
            }
            if (0 != surface_create(&g_geo, cw, ch, cx - cw / 2, cy - ch / 2, "YolovaimHUD"))
                return; // 本帧放弃,下帧重试
            g_geo_created_gw = s.geoW;
            g_geo_created_gh = s.geoH;
            g_check_fullscreen = s.checkMode;
        }
        DrawCtx ctx{nullptr, s.geoW, s.geoH, g_geo.x, g_geo.y};
        present_layer(&g_geo, ctx, draw_geometry, s);
    } else if (g_geo.win) {
        // 无几何内容直接撤层:连静态 layer 的合成/带宽都不留
        surface_destroy(&g_geo);
        g_check_fullscreen = false;
    }

    const bool textContent = s.showInfo && s.textValid && !s.text.empty();
    if (textContent) {
        const int tw = s.geoW < 960 ? s.geoW : 960;
        const int th = 120;
        if (!g_text.win || g_text_created_gw != s.geoW || g_text.w != tw) {
            surface_destroy(&g_text);
            if (0 != surface_create(&g_text, tw, th, (s.geoW - tw) / 2, 24, "YolovaimHUDText"))
                return;
            g_text_created_gw = s.geoW;
        }
        DrawCtx ctx{nullptr, s.geoW, s.geoH, g_text.x, g_text.y};
        present_layer(&g_text, ctx, draw_text, s);
    } else if (g_text.win) {
        surface_destroy(&g_text);
    }
}

static void render_thread_func() {
    // HUD 是纯显示,比游戏/注入都次要:降优先级,绝不抢大核
    setpriority(PRIO_PROCESS, 0, 10);
    while (true) {
        HudState local;
        {
            std::unique_lock<std::mutex> lk(g_mutex);
            g_cv.wait(lk, [] { return !g_running || g_dirty; });
            if (!g_running)
                break;
            g_dirty = false;
            local = g_state;
        }
        render(local);
    }
}

// =========================================================================
// 生命周期
// =========================================================================

int renderer_start() {
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (g_started)
            return 0;
    }

    if (!surface_symbols_ready()) {
        ALOG(ANDROID_LOG_ERROR, "symbols not resolved, anti-capture unsupported on this ROM");
        return -1;
    }

    // 试建小 layer + 试一次 lock/post:立即暴露创建/绘制路径失败,
    // 让 HUD_ON 的返回值对上层兜底决策仍然可靠(此刻还不知道采集
    // 几何,真正的 layer 由渲染线程按需创建)。
    {
        HudSurface probe{};
        int rc = surface_create(&probe, 64, 64, 0, 0, "YolovaimHUD_probe");
        if (0 != rc)
            return rc;
        ANativeWindow_Buffer buf{};
        bool ok = (0 == ANativeWindow_lock(probe.win, &buf, nullptr));
        if (ok) {
            uint32_t *bits = reinterpret_cast<uint32_t *>(buf.bits);
            for (int y = 0; y < buf.height; ++y) {
                uint32_t *row = bits + static_cast<size_t>(y) * buf.stride;
                for (int x = 0; x < buf.width; ++x)
                    row[x] = 0u;
            }
            ok = (0 == ANativeWindow_unlockAndPost(probe.win));
        }
        surface_destroy(&probe);
        if (!ok) {
            ALOG(ANDROID_LOG_ERROR, "probe lock/post failed");
            return -3;
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_mutex);
        g_running = true;
        g_started = true;
        g_dirty = false;
        g_render_thread = std::thread(render_thread_func);
    }
    ALOG(ANDROID_LOG_INFO, "renderer started (on-demand layers)");
    return 0;
}

void renderer_stop() {
    std::thread local;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (!g_started)
            return;
        g_running = false;
        local = std::move(g_render_thread);
    }
    g_cv.notify_all();
    if (local.joinable())
        local.join(); // 最多等完一次进行中的渲染
    // join 之后再销毁,避免与渲染竞争窗口
    surface_destroy(&g_geo);
    surface_destroy(&g_text);
    g_geo_created_gw = g_geo_created_gh = g_text_created_gw = 0;
    g_check_fullscreen = false;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        g_started = false;
        g_dirty = false;
    }
    ALOG(ANDROID_LOG_INFO, "renderer stopped");
}

// =========================================================================
// 状态更新(IPC 读线程调用,全部立即返回)
// =========================================================================

void set_geo(int w, int h) {
    if (w <= 0 || h <= 0)
        return;
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.geoW == w && g_state.geoH == h)
        return;
    // 尺寸变化(通常来自旋转)→ 置脏,渲染线程发现采集几何与 layer
    // 创建时不符会销毁重建(尺寸+位置,见 render)
    g_state.geoW = w;
    g_state.geoH = h;
    g_dirty = true;
    g_cv.notify_all();
}

void set_toggle(const char *what, int on) {
    std::lock_guard<std::mutex> lk(g_mutex);
    bool *target = nullptr;
    if (strcmp(what, "captureRange") == 0) target = &g_state.showRange;
    else if (strcmp(what, "fov") == 0) target = &g_state.showFov;
    else if (strcmp(what, "box") == 0) target = &g_state.showBox;
    else if (strcmp(what, "centerDot") == 0) target = &g_state.showDot;
    else if (strcmp(what, "inferInfo") == 0) target = &g_state.showInfo;
    if (nullptr == target) {
        ALOG(ANDROID_LOG_WARN, "set_toggle: unknown key '%s'", what);
        return;
    }
    if (*target == (on != 0))
        return;
    *target = (on != 0);
    g_dirty = true;
    g_cv.notify_all();
}

void set_check_mode(int on) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.checkMode == (on != 0))
        return;
    g_state.checkMode = (on != 0);
    g_dirty = true;
    g_cv.notify_all();
}

void set_fov(int r) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.fovRadius == r || !g_state.showFov)
        return; // 值没变 / 不可见时不触发重绘
    g_state.fovRadius = r;
    g_dirty = true;
    g_cv.notify_all();
}

void set_range(int r) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.rangeRadius == r || !g_state.showRange)
        return;
    g_state.rangeRadius = r;
    g_dirty = true;
    g_cv.notify_all();
}

void set_boxes(int n, const int *xyxy) {
    if (n > MAX_BOXES)
        n = MAX_BOXES;
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_state.showBox) {
        // 开关关着也存:重新打开时下一帧会覆盖,这里省一次重绘
        g_state.nBoxes = n;
        for (int i = 0; i < n; i++)
            for (int k = 0; k < 4; k++)
                g_state.boxes[i][k] = xyxy[i * 4 + k];
        return;
    }
    if (g_state.nBoxes == n && 0 == memcmp(g_state.boxes, xyxy, sizeof(int) * 4 * n))
        return;
    g_state.nBoxes = n;
    for (int i = 0; i < n; i++)
        for (int k = 0; k < 4; k++)
            g_state.boxes[i][k] = xyxy[i * 4 + k];
    g_dirty = true;
    g_cv.notify_all();
}

void text_begin(int w, int h) {
    if (w <= 0 || h <= 0 || w > 4096 || h > 512)
        return;
    std::lock_guard<std::mutex> lk(g_mutex);
    g_text_pending.assign(static_cast<size_t>(w) * h, 0u);
    g_text_pending_w = w;
    g_text_pending_h = h;
    g_text_in_begin = true;
}

void text_run(int y, int x, int len, uint32_t argb) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_text_in_begin)
        return;
    if (y < 0 || y >= g_text_pending_h || x < 0 || len <= 0)
        return;
    for (int i = 0; i < len; i++) {
        int px = x + i;
        if (px < g_text_pending_w)
            g_text_pending[static_cast<size_t>(y) * g_text_pending_w + px] = argb;
    }
}

void text_end() {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_text_in_begin)
        return;
    g_text_in_begin = false;
    g_state.text = std::move(g_text_pending);
    g_state.textW = g_text_pending_w;
    g_state.textH = g_text_pending_h;
    g_text_pending.clear();
    g_text_pending.shrink_to_fit();
    g_state.textValid = true;
    if (g_state.showInfo) {
        g_dirty = true;
        g_cv.notify_all();
    }
}

} // namespace hud
