/*
 * hud_renderer.cpp — 防捕获 HUD 的状态存储与绘制。
 *
 * 绘制元素与旧 OverlayCanvasView(兜底渲染)的视觉参数逐项对齐:
 *   FOV 圈      2px 描边,白 0xAA 透明度,半径动态
 *   四角框      4px 白不透明线,角长 36px,离角点 10px 起笔
 *   中心点      4px 半径实心圆,白 0xAA
 *   检测框      2px 描边,白 0x99
 *   推理信息    顶部居中,半透明黑底药丸 + 浅黄文字
 *
 * 坐标系:真机 dumpsys SurfaceFlinger 实证(RMX3366 / A13,横屏时
 * layerStackSpace=2400x1080 而 framebufferSpace=1080x2400+ROTATION_90):
 * layer 生活在 layer-stack 逻辑空间,该空间**跟随屏幕旋转**,旋转发生在
 * layer-stack → framebuffer 之间。而 MediaProjection 采集帧也是逻辑
 * 方向 —— 所以采集坐标 == layer 坐标,恒等映射,无需旋转变换。
 *
 * 性能设计(2026-09-02 重做,目标:与不开防录屏的兜底路线开销持平):
 *   兜底路线是一个全屏半透明悬浮窗,每次内容变化只重绘脏区。native 路线
 *   必须做到同一件事,否则防录屏就变成了"开了就卡"。
 *
 *   1. 几何 layer 固定为全屏尺寸,只在旋转(采集几何变化)时重建。
 *      之前按内容包围盒缩放 layer 的做法有两个反效果:检测框靠近屏幕
 *      边缘就会涨到全屏且**再也缩不回来**(收缩判据要求 w、h 同时缩到
 *      2/3,而横屏 h 被钳在屏高恒等于 layer 高,永远不成立);只开检测框
 *      时又会随目标出现/消失反复 destroy+create 全屏 layer(每次 3 个
 *      全屏 gralloc buffer + SF 事务)。
 *   2. 每帧只清「本帧内容包围盒 ∪ 该 buffer 上次的内容包围盒」。三缓冲
 *      下不能只清本帧脏区(buffer 轮换会带出前几帧残影),但按 buffer
 *      记住各自上次画到哪就可以 —— 全屏清屏 10.4MB/帧 因此降到通常
 *      不足 200KB/帧,且与"画了多少"成正比而不是与屏幕面积成正比。
 *   3. 推理信息走 8bit alpha 掩码一次性提交(见 set_text_mask),layer
 *      尺寸按 128px 桶量化,避免文字宽度随数字变化而反复重建 layer。
 *   4. 渲染线程降优先级,绝不和游戏抢大核。
 */

#include "hud_renderer.h"
#include "hud_surface.h"

#include <android/log.h>
#include <android/native_window.h>

#include <cmath>
#include <condition_variable>
#include <cstring>
#include <time.h>
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

// 线条最粗 4px,且 corner_box/检测框的右下边会从坐标处再往外画 th-1 px,
// 包围盒统一留这个余量。
static const int STROKE_PAD = 6;

// 推理信息药丸在采集空间的顶边(与旧 TextView 的 dp(12)+状态栏落点一致)
static const int TEXT_TOP = 40;

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

    int geoW = 0; // 采集空间宽高(MediaProjection 坐标系,= layer-stack 空间)
    int geoH = 0;

    int fovRadius = 50;
    int rangeRadius = 300;

    int nBoxes = 0;
    int boxes[MAX_BOXES][4] = {}; // x1 y1 x2 y2,采集空间

    bool textValid = false;
    int textW = 0;
    int textH = 0;
    uint32_t textFg = 0xFFFFEB3Bu;
    uint32_t textBg = 0xCC101010u;
    std::vector<uint8_t> mask; // 8bit 覆盖率,packed w*h
};

static std::mutex g_mutex;
static std::condition_variable g_cv;
static bool g_running = false; // 渲染线程存活
static bool g_started = false; // renderer_start 成功过
static bool g_dirty = false;
static std::thread g_render_thread;
static HudState g_state;

// =========================================================================
// 矩形(半开区间 [l,r) x [t,b),r<=l 表示空)
// =========================================================================

struct Rect {
    int l = 0, t = 0, r = 0, b = 0;
};

static inline bool rect_empty(const Rect &x) { return x.r <= x.l || x.b <= x.t; }

static inline void rect_add(Rect *acc, int l, int t, int r, int b) {
    if (r <= l || b <= t)
        return;
    if (rect_empty(*acc)) {
        acc->l = l; acc->t = t; acc->r = r; acc->b = b;
        return;
    }
    if (l < acc->l) acc->l = l;
    if (t < acc->t) acc->t = t;
    if (r > acc->r) acc->r = r;
    if (b > acc->b) acc->b = b;
}

static inline Rect rect_union(const Rect &a, const Rect &b) {
    if (rect_empty(a)) return b;
    if (rect_empty(b)) return a;
    Rect o = a;
    rect_add(&o, b.l, b.t, b.r, b.b);
    return o;
}

static inline Rect rect_clip(const Rect &x, int w, int h) {
    Rect o = x;
    if (o.l < 0) o.l = 0;
    if (o.t < 0) o.t = 0;
    if (o.r > w) o.r = w;
    if (o.b > h) o.b = h;
    if (rect_empty(o)) return Rect{};
    return o;
}

// =========================================================================
// 绘制
// =========================================================================

struct DrawCtx {
    ANativeWindow_Buffer *buf;
    int gw, gh;   // 采集空间尺寸(== layer-stack 尺寸,恒等映射)
    int ox, oy;   // 当前 layer 左上角在采集空间的位置
};

// 采集空间 → layer 内部像素:恒等映射 + 平移到 layer 原点。
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
        // 不会天然出现;中心 r=12 实心圆 + 屏幕 1/4 大圆环,采样容易
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

// 本帧几何内容的包围盒(采集空间,保守超集 —— 宁可多清几行也不能少)。
static Rect geometry_bbox(const HudState &s) {
    const int cx = s.geoW / 2, cy = s.geoH / 2;
    Rect box{};

    if (s.checkMode) {
        int r = (s.geoW < s.geoH ? s.geoW : s.geoH) / 4 + STROKE_PAD;
        rect_add(&box, cx - r, cy - r, cx + r, cy + r);
        return rect_clip(box, s.geoW, s.geoH);
    }
    if (s.showDot)
        rect_add(&box, cx - 4 - STROKE_PAD, cy - 4 - STROKE_PAD,
                       cx + 4 + STROKE_PAD, cy + 4 + STROKE_PAD);
    if (s.showRange) {
        int h = s.rangeRadius + STROKE_PAD;
        rect_add(&box, cx - h, cy - h, cx + h, cy + h);
    }
    if (s.showFov && s.fovRadius > 0) {
        int h = s.fovRadius + STROKE_PAD;
        rect_add(&box, cx - h, cy - h, cx + h, cy + h);
    }
    if (s.showBox) {
        for (int i = 0; i < s.nBoxes; i++) {
            const int *b = s.boxes[i];
            int l = b[0] < b[2] ? b[0] : b[2];
            int r = b[0] < b[2] ? b[2] : b[0];
            int t = b[1] < b[3] ? b[1] : b[3];
            int bo = b[1] < b[3] ? b[3] : b[1];
            rect_add(&box, l - STROKE_PAD, t - STROKE_PAD, r + STROKE_PAD, bo + STROKE_PAD);
        }
    }
    return rect_clip(box, s.geoW, s.geoH);
}

// =========================================================================
// 文字掩码合成
// =========================================================================

// 覆盖率 → 最终像素的查表(SRC_OVER,输出非预乘,与旧路线 Kotlin 端
// Canvas.drawText + getPixels 的取值口径一致)。把药丸底从位图挪到
// daemon 侧按覆盖率合成,视觉不变:覆盖率 0 得到纯 bg、255 得到纯 fg,
// 中间值与参考公式零偏差(见提交说明里的宿主机对照)。
static uint32_t g_text_lut[256];
static uint32_t g_text_lut_fg = 0;
static uint32_t g_text_lut_bg = 0;
static bool g_text_lut_ready = false;

static void build_text_lut(uint32_t fg, uint32_t bg) {
    if (g_text_lut_ready && g_text_lut_fg == fg && g_text_lut_bg == bg)
        return;
    const int fa = static_cast<int>((fg >> 24) & 0xFF);
    const int fr = static_cast<int>((fg >> 16) & 0xFF);
    const int fgr = static_cast<int>((fg >> 8) & 0xFF);
    const int fb = static_cast<int>(fg & 0xFF);
    const int ba = static_cast<int>((bg >> 24) & 0xFF);
    const int br = static_cast<int>((bg >> 16) & 0xFF);
    const int bgr = static_cast<int>((bg >> 8) & 0xFF);
    const int bb = static_cast<int>(bg & 0xFF);
    for (int c = 0; c < 256; c++) {
        const int sa = fa * c / 255;        // 源有效 alpha
        const int inv = 255 - sa;
        // 全程放大 255 倍算,最后一次除法才落地 —— 先预乘再反预乘会在
        // 覆盖率 0 处把 0x10 的底色磨成 0x0F(实测,整数取整损失)。
        const int oa255 = sa * 255 + ba * inv;   // = 输出 alpha x 255
        if (oa255 <= 0) {
            g_text_lut[c] = 0u;
            continue;
        }
        int oa = (oa255 + 127) / 255;
        if (oa > 255) oa = 255;
        int r = (fr * sa * 255 + br * ba * inv + oa255 / 2) / oa255;
        int g = (fgr * sa * 255 + bgr * ba * inv + oa255 / 2) / oa255;
        int b = (fb * sa * 255 + bb * ba * inv + oa255 / 2) / oa255;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        g_text_lut[c] = argb_to_rgba(static_cast<uint32_t>(
            (oa << 24) | (r << 16) | (g << 8) | b));
    }
    g_text_lut_fg = fg;
    g_text_lut_bg = bg;
    g_text_lut_ready = true;
}

// =========================================================================
// layer 管理(渲染线程私有,无需加锁)
// =========================================================================

static HudSurface g_geo;  // 几何元素(全屏,固定尺寸)
static HudSurface g_text; // 推理信息药丸(顶部小条)
static int g_geo_created_gw = 0, g_geo_created_gh = 0;
static int g_text_created_gw = 0;

// 每个 buffer 上次画到哪 —— 三缓冲(个别 ROM 四缓冲)下按 buffer 各记一份,
// 才能只清自己那份的残影。未见过的 bits 指针一律整层清一次,保证正确。
struct BufferDirty {
    void *bits = nullptr;
    Rect rect;
};
static BufferDirty g_geo_dirty[8];

static void geo_dirty_reset() {
    for (int i = 0; i < 8; i++) {
        g_geo_dirty[i].bits = nullptr;
        g_geo_dirty[i].rect = Rect{};
    }
}

// 统计:每 120 帧一行,给"还卡不卡"留下可对比的数字而不是感觉。
static uint64_t g_stat_frames = 0;
static uint64_t g_stat_clear_px = 0;
static uint64_t g_stat_us = 0;

// 格式不符只告警一次:每帧刷日志会把 logcat 冲穿(main 缓冲默认 256KB)
static void warn_format_once(int32_t format) {
    static bool warned = false;
    if (1 /* WINDOW_FORMAT_RGBA_8888 */ == format || warned)
        return;
    warned = true;
    ALOG(ANDROID_LOG_ERROR, "unexpected window format %d (expected RGBA_8888); "
                            "HUD colors will be wrong on this ROM", format);
}

static inline uint64_t now_us() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + ts.tv_nsec / 1000;
}

static void present_geometry(const HudState &s) {
    ANativeWindow_Buffer buf{};
    if (0 != ANativeWindow_lock(g_geo.win, &buf, nullptr)) {
        ALOG(ANDROID_LOG_ERROR, "render: geo lock failed (%dx%d)", g_geo.w, g_geo.h);
        return;
    }
    const uint64_t t0 = now_us();
    const Rect cur = geometry_bbox(s);

    BufferDirty *slot = nullptr;
    for (int i = 0; i < 8; i++) {
        if (g_geo_dirty[i].bits == buf.bits) { slot = &g_geo_dirty[i]; break; }
    }
    Rect clear;
    if (nullptr == slot) {
        // 新 buffer:内容未知,整层清一次并占一个槽位
        clear = Rect{0, 0, buf.width, buf.height};
        for (int i = 0; i < 8; i++) {
            if (nullptr == g_geo_dirty[i].bits) { slot = &g_geo_dirty[i]; break; }
        }
        if (nullptr == slot) slot = &g_geo_dirty[0]; // 缓冲数超预期,退化为复用槽位
    } else {
        clear = rect_union(slot->rect, cur);
    }
    clear = rect_clip(clear, buf.width, buf.height);

    // 清屏一律清成透明。createSurface 明确要的是 RGBA_8888(format=1),
    // 真拿到别的格式就是本 ROM 不支持,这里绝不能像旧实现那样退化成
    // 不透明黑 —— 几何 layer 现在恒为全屏,那等于把整块屏幕刷黑。
    warn_format_once(buf.format);
    const uint32_t bg = 0u;
    uint32_t *bits = reinterpret_cast<uint32_t *>(buf.bits);
    for (int y = clear.t; y < clear.b; y++) {
        uint32_t *row = bits + static_cast<size_t>(y) * buf.stride;
        for (int x = clear.l; x < clear.r; x++)
            row[x] = bg;
    }

    DrawCtx ctx{&buf, s.geoW, s.geoH, g_geo.x, g_geo.y};
    draw_geometry(ctx, s);

    slot->bits = buf.bits;
    slot->rect = cur;

    g_stat_clear_px += static_cast<uint64_t>(clear.r - clear.l) * (clear.b - clear.t);
    g_stat_us += now_us() - t0;
    g_stat_frames++;

    if (0 != ANativeWindow_unlockAndPost(g_geo.win))
        ALOG(ANDROID_LOG_ERROR, "render: geo unlockAndPost failed");

    if ((g_stat_frames % 120) == 0) {
        ALOG(ANDROID_LOG_INFO, "render stat: %llu frames, avg clear %llu px, avg draw %llu us",
             static_cast<unsigned long long>(g_stat_frames),
             static_cast<unsigned long long>(g_stat_clear_px / 120),
             static_cast<unsigned long long>(g_stat_us / 120));
        g_stat_clear_px = 0;
        g_stat_us = 0;
    }
}

// 文字 layer:每个像素每帧都被覆盖写(掩码区查表,其余透明),不需要清屏
// 也不需要脏区跟踪。
static void present_text(const HudState &s) {
    ANativeWindow_Buffer buf{};
    if (0 != ANativeWindow_lock(g_text.win, &buf, nullptr)) {
        ALOG(ANDROID_LOG_ERROR, "render: text lock failed (%dx%d)", g_text.w, g_text.h);
        return;
    }
    build_text_lut(s.textFg, s.textBg);
    warn_format_once(buf.format);
    const uint32_t clr = 0u;
    const int ox = (buf.width - s.textW) / 2;
    uint32_t *bits = reinterpret_cast<uint32_t *>(buf.bits);
    for (int y = 0; y < buf.height; y++) {
        uint32_t *row = bits + static_cast<size_t>(y) * buf.stride;
        if (y >= s.textH) {
            for (int x = 0; x < buf.width; x++)
                row[x] = clr;
            continue;
        }
        const uint8_t *src = s.mask.data() + static_cast<size_t>(y) * s.textW;
        int x = 0;
        for (; x < ox && x < buf.width; x++)
            row[x] = clr;
        for (int i = 0; i < s.textW && x < buf.width; i++, x++)
            row[x] = g_text_lut[src[i]];
        for (; x < buf.width; x++)
            row[x] = clr;
    }
    if (0 != ANativeWindow_unlockAndPost(g_text.win))
        ALOG(ANDROID_LOG_ERROR, "render: text unlockAndPost failed");
}

// 128px 桶量化:文字宽度随 fps 数字变化每帧都在抖(950→957→950),按实际
// 宽度建 layer 会退化成每帧重建。
static inline int bucket(int v, int step) { return ((v + step - 1) / step) * step; }

static void render(const HudState &s) {
    if (s.geoW <= 0 || s.geoH <= 0)
        return;

    const bool geoContent = s.checkMode || s.showFov || s.showRange || s.showBox || s.showDot;
    if (geoContent) {
        // 固定全屏,只在采集几何变化(旋转)时重建
        if (!g_geo.win || g_geo_created_gw != s.geoW || g_geo_created_gh != s.geoH) {
            surface_destroy(&g_geo);
            geo_dirty_reset();
            if (0 != surface_create(&g_geo, s.geoW, s.geoH, 0, 0, "YolovaimHUD"))
                return; // 本帧放弃,下帧重试
            g_geo_created_gw = s.geoW;
            g_geo_created_gh = s.geoH;
        }
        present_geometry(s);
    } else if (g_geo.win) {
        // 无几何内容直接撤层:连静态 layer 的合成/带宽都不留
        surface_destroy(&g_geo);
        geo_dirty_reset();
        g_geo_created_gw = g_geo_created_gh = 0;
    }

    const bool textContent = s.showInfo && s.textValid && s.textW > 0 && s.textH > 0 &&
                             s.mask.size() >= static_cast<size_t>(s.textW) * s.textH;
    if (textContent) {
        int lw = bucket(s.textW, 128);
        if (lw > s.geoW) lw = s.geoW;
        const int lh = bucket(s.textH, 8);
        const int lx = (s.geoW - lw) / 2;
        if (!g_text.win || g_text.w != lw || g_text.h != lh || g_text_created_gw != s.geoW) {
            surface_destroy(&g_text);
            if (0 != surface_create(&g_text, lw, lh, lx < 0 ? 0 : lx, TEXT_TOP, "YolovaimHUDText"))
                return;
            g_text_created_gw = s.geoW;
        }
        present_text(s);
    } else if (g_text.win) {
        surface_destroy(&g_text);
        g_text_created_gw = 0;
    }
}

static void render_thread_func() {
    // HUD 是纯显示,比游戏/注入都次要:降优先级,绝不抢大核
    setpriority(PRIO_PROCESS, 0, 10);
    // 复用同一份 local:赋值会复用 vector 容量,热路径上不再每帧分配掩码
    HudState local;
    while (true) {
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
    ALOG(ANDROID_LOG_INFO, "renderer started (fullscreen geo layer + dirty-rect clear)");
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
    geo_dirty_reset();
    g_geo_created_gw = g_geo_created_gh = g_text_created_gw = 0;
    g_stat_frames = g_stat_clear_px = g_stat_us = 0;
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
    // 创建时不符会销毁重建(见 render)
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

// 隐藏时也存值,只是不触发重绘 —— 之前直接 return 会丢掉隐藏期间的变化,
// 而 Kotlin 侧只在"值变了"时才下发,于是再打开时画的是过期半径且永远
// 不会被纠正。
void set_fov(int r) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.fovRadius == r)
        return;
    g_state.fovRadius = r;
    if (!g_state.showFov)
        return;
    g_dirty = true;
    g_cv.notify_all();
}

void set_range(int r) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_state.rangeRadius == r)
        return;
    g_state.rangeRadius = r;
    if (!g_state.showRange)
        return;
    g_dirty = true;
    g_cv.notify_all();
}

void set_boxes(int n, const int *xyxy) {
    if (n > MAX_BOXES)
        n = MAX_BOXES;
    if (n < 0)
        n = 0;
    std::lock_guard<std::mutex> lk(g_mutex);
    bool same = (g_state.nBoxes == n) &&
                (0 == memcmp(g_state.boxes, xyxy, sizeof(int) * 4 * static_cast<size_t>(n)));
    g_state.nBoxes = n;
    for (int i = 0; i < n; i++)
        for (int k = 0; k < 4; k++)
            g_state.boxes[i][k] = xyxy[i * 4 + k];
    if (same || !g_state.showBox)
        return; // 开关关着也存值,重新打开时下一帧自然是新的
    g_dirty = true;
    g_cv.notify_all();
}

void set_text_mask(int w, int h, uint32_t fg, uint32_t bg,
                   const uint8_t *mask, size_t n) {
    if (w <= 0 || h <= 0 || nullptr == mask)
        return;
    const size_t need = static_cast<size_t>(w) * static_cast<size_t>(h);
    if (n < need)
        return;
    std::lock_guard<std::mutex> lk(g_mutex);
    g_state.mask.assign(mask, mask + need); // 容量复用,不会每帧重新分配
    g_state.textW = w;
    g_state.textH = h;
    g_state.textFg = fg;
    g_state.textBg = bg;
    g_state.textValid = true;
    if (!g_state.showInfo)
        return;
    g_dirty = true;
    g_cv.notify_all();
}

} // namespace hud
