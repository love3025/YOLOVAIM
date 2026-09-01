/*
 * hud_renderer.cpp — 防捕获 HUD 的状态存储与绘制。
 *
 * 绘制元素与旧 OverlayCanvasView(兜底渲染)的视觉参数逐项对齐:
 *   FOV 圈      2px 描边,白 0xAA 透明度,半径动态
 *   四角框      4px 白不透明线,角长 36px,离角点 10px 起笔
 *   中心点      4px 半径实心圆,白 0xAA
 *   检测框      2px 描边,白 0x99
 *   推理信息    顶部居中 blit Kotlin 传来的 ARGB 位图
 * 用户在物理屏上看到的应与迁移前基本一致(改进方案.md §7)。
 *
 * 旋转:layer 生活在 layer-stack 空间(自然方向);采集空间(MediaProjection
 * 输出帧,= 用户看到的屏幕方向)到 layer 空间的旋转变换在 plot() 内完成。
 * 方向值来自 SurfaceFlinger DisplayState.orientation。
 */

#include "hud_renderer.h"
#include "hud_surface.h"
#include "ANativeWindowCreator.h"

#include <android/log.h>
#include <android/native_window.h>

#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define ALOG(level, fmt, ...) \
    __android_log_print(level, "YolovaimHUD", fmt, ##__VA_ARGS__)

namespace hud {

static const int MAX_BOXES = 16;

struct HudState {
    bool showFov = false;
    bool showRange = false;
    bool showBox = false;
    bool showDot = false;
    bool showInfo = false;

    // 自检模式:无视正常开关,只画洋红色检查图案(中心实心圆 + 大圆环)。
    // 用游戏画面不可能出现的颜色做判据,避免与游戏自身 UI(比如屏幕
    // 中心的准星)撞车 —— 首版用"白像素"判据就栽在这上面。
    bool checkMode = false;

    int geoW = 0; // 采集空间宽高(MediaProjection 坐标系)
    int geoH = 0;
    int orientation = 0;

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

// 文字位图的待写入缓冲(begin→row*→end 期间 accumulate,不参与渲染)
static std::vector<uint32_t> g_text_pending;
static int g_text_pending_w = 0;
static int g_text_pending_h = 0;
static bool g_text_in_begin = false;

// =========================================================================
// 绘制
// =========================================================================

struct DrawCtx {
    ANativeWindow_Buffer *buf;
    int orientation;
    int gw, gh; // 采集空间尺寸
};

// 采集空间 → layer 空间。方向 1/3 时 layer 尺寸为 (gh, gw)。
// 注意:90° 系两个方向的选取基于 Android 旋转约定推导,真机验收时
// 若检测框出现镜像/错位,交换 case 1 / case 3 的公式即可。
static inline void xform(const DrawCtx &c, int x, int y, int *lx, int *ly) {
    switch (c.orientation) {
    case 1:
        *lx = y;
        *ly = c.gw - x;
        break;
    case 3:
        *lx = c.gh - y;
        *ly = x;
        break;
    case 2:
        *lx = c.gw - x;
        *ly = c.gh - y;
        break;
    default:
        *lx = x;
        *ly = y;
        break;
    }
}

static inline void plot(const DrawCtx &c, int x, int y, uint32_t rgba) {
    if (x < 0 || y < 0 || x >= c.gw || y >= c.gh)
        return;
    int lx, ly;
    xform(c, x, y, &lx, &ly);
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

static void blit_text(const DrawCtx &c, const HudState &s) {
    if (!s.textValid || s.textW <= 0 || s.textH <= 0)
        return;
    int x0 = (s.geoW - s.textW) / 2;
    int y0 = 40; // 顶部居中,约等于旧 TextView(dp(12)+状态栏)的落点
    for (int y = 0; y < s.textH; y++) {
        const uint32_t *src = s.text.data() + static_cast<size_t>(y) * s.textW;
        for (int x = 0; x < s.textW; x++) {
            if ((src[x] & 0xFF000000u) != 0)
                plot(c, x0 + x, y0 + y, argb_to_rgba(src[x]));
        }
    }
}

static void render(const HudState &s) {
    ANativeWindow *w = surface_window();
    if (nullptr == w)
        return;

    ANativeWindow_Buffer buf{};
    if (0 != ANativeWindow_lock(w, &buf, nullptr)) {
        ALOG(ANDROID_LOG_ERROR, "render: ANativeWindow_lock failed fmt=%d", buf.format);
        return;
    }

    // 清屏:格式带 alpha 时全透明,否则退化为黑底(实测要点,方案 §3.3)
    uint32_t bg = (buf.format == 1 /* WINDOW_FORMAT_RGBA_8888 */) ? 0u : 0xFF000000u;
    uint32_t *bits = reinterpret_cast<uint32_t *>(buf.bits);
    for (int y = 0; y < buf.height; ++y) {
        uint32_t *row = bits + static_cast<size_t>(y) * buf.stride;
        for (int x = 0; x < buf.width; ++x)
            row[x] = bg;
    }

    bool any = s.showFov || s.showRange || s.showBox || s.showDot ||
               (s.showInfo && s.textValid && !s.text.empty());
    if (s.checkMode) {
        // 自检图案:洋红(0xFFFF00FF,ARGB/RGBA 数值相同),游戏画面
        // 不会天然出现;中心 r=12 实心圆 + 半屏 1/4 大圆环,采样容易
        DrawCtx ctx{&buf, s.orientation, s.geoW, s.geoH};
        const int cx = s.geoW / 2;
        const int cy = s.geoH / 2;
        const uint32_t magenta = 0xFFFF00FFu;
        fill_circle(ctx, cx, cy, 12, magenta);
        int r = (s.geoW < s.geoH ? s.geoW : s.geoH) / 4;
        circle(ctx, cx, cy, static_cast<float>(r), 4, magenta);
    } else if (any) {
        DrawCtx ctx{&buf, s.orientation, s.geoW, s.geoH};
        const int cx = s.geoW / 2;
        const int cy = s.geoH / 2;

        // 颜色对齐 OverlayCanvasView 的 Paint:
        // corner=不透明白,fov/dot=0xAA 白,box=0x99 白(灰阶+对称
        // alpha 下 RGBA 与 ARGB 数值恰好相同,直接写)
        const uint32_t colCorner = 0xFFFFFFFFu;
        const uint32_t colFov = 0xAAFFFFFFu;
        const uint32_t colDot = 0xAAFFFFFFu;
        const uint32_t colBox = 0x99FFFFFFu;

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

        if (s.showInfo)
            blit_text(ctx, s);
    }

    if (0 != ANativeWindow_unlockAndPost(w))
        ALOG(ANDROID_LOG_ERROR, "render: unlockAndPost failed");
}

static void render_thread_func() {
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

    int rc = surface_create();
    if (0 != rc)
        return rc;

    surface_refresh_display_info();
    auto info = android::ANativeWindowCreator::GetDisplayInfo();
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        g_state.orientation = info.orientation;
        // 默认采集空间 = 当前屏幕方向下的尺寸;Kotlin 随后用 HUD_GEO
        // 校准成 MediaProjection 的精确值。
        g_state.geoW = info.width;
        g_state.geoH = info.height;
    }

    // 首帧空渲染:立即暴露 lock/post 失败,而不是等第一条状态命令。
    // 此刻渲染线程还没起,无需加锁读。
    render(g_state);

    {
        std::lock_guard<std::mutex> lk(g_mutex);
        g_running = true;
        g_started = true;
        g_dirty = false;
        g_render_thread = std::thread(render_thread_func);
    }
    ALOG(ANDROID_LOG_INFO, "renderer started, geo=%dx%d orient=%d",
         info.width, info.height, info.orientation);
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
    surface_destroy(); // join 之后再销毁,避免与渲染竞争窗口
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
    surface_refresh_display_info(); // 采集尺寸变化通常伴随旋转
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.geoW == w && g_state.geoH == h &&
        g_state.orientation == surface_orientation())
        return;
    g_state.geoW = w;
    g_state.geoH = h;
    g_state.orientation = surface_orientation();
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
