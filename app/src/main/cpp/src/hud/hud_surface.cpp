/*
 * hud_surface.cpp — 防捕获 HUD layer 的生命周期管理。
 *
 * 实现全部落在 anti-capture-demo 移植来的 ANativeWindowCreator 上
 * (改进方案.md §3:该 demo 是唯一在 RMX3366 实测通过的参考实现)。
 * 日志走 logcat(tag YolovaimHUD)+ stderr;stdout 是 IPC 通道,不碰。
 */

#include "hud_surface.h"
#include "ANativeWindowCreator.h"

#include <android/log.h>

#define ALOG(level, fmt, ...) \
    __android_log_print(level, "YolovaimHUD", fmt, ##__VA_ARGS__)

namespace hud {

static ANativeWindow *g_window = nullptr;
static int g_layer_w = 0;
static int g_layer_h = 0;

bool surface_symbols_ready() {
    return android::ANativeWindowCreator::SymbolsReady();
}

int surface_create() {
    if (g_window)
        return 0;

    if (!android::ANativeWindowCreator::SymbolsReady()) {
        ALOG(ANDROID_LOG_ERROR, "symbols not resolved, anti-capture unsupported on this ROM");
        return -1;
    }

    // GetDisplayInfo 返回按当前屏幕方向换算后的逻辑尺寸(内部已处理
    // orientation 交换),即 layer-stack 空间尺寸。用显式尺寸创建并记录,
    // 供渲染线程判断"几何变了但 layer 还是旧尺寸"→ 触发重建。
    auto info = android::ANativeWindowCreator::GetDisplayInfo();
    // z=100:压过应用窗口和应用型悬浮窗,又不与系统关键层抢位置,
    // 与 demo PoC 实测参数一致;skipScrenshot=true 打防捕获标记。
    g_window = android::ANativeWindowCreator::Create("YolovaimHUD", info.width, info.height, true, 100);
    if (nullptr == g_window) {
        ALOG(ANDROID_LOG_ERROR, "layer/window creation failed");
        return -2;
    }
    ANativeWindow_acquire(g_window);
    g_layer_w = info.width;
    g_layer_h = info.height;
    ALOG(ANDROID_LOG_INFO, "anti-capture layer created %dx%d", g_layer_w, g_layer_h);
    return 0;
}

void surface_destroy() {
    if (nullptr == g_window)
        return;
    ANativeWindow_release(g_window);
    android::ANativeWindowCreator::Destroy(g_window);
    g_window = nullptr;
    g_layer_w = 0;
    g_layer_h = 0;
    ALOG(ANDROID_LOG_INFO, "anti-capture layer destroyed");
}

ANativeWindow *surface_window() {
    return g_window;
}

void surface_layer_dims(int *w, int *h) {
    *w = g_layer_w;
    *h = g_layer_h;
}

} // namespace hud
