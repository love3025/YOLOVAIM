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
static int g_orientation = 0;

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

    surface_refresh_display_info();

    // 尺寸 -1,-1 = 跟随当前显示器 layer-stack 空间;skipScrenshot=true
    // 打防捕获标记;z=100 与 demo PoC 实测参数一致。
    g_window = android::ANativeWindowCreator::Create("YolovaimHUD", -1, -1, true, 100);
    if (nullptr == g_window) {
        ALOG(ANDROID_LOG_ERROR, "layer/window creation failed");
        return -2;
    }
    ANativeWindow_acquire(g_window);
    ALOG(ANDROID_LOG_INFO, "anti-capture layer created, orientation=%d", g_orientation);
    return 0;
}

void surface_destroy() {
    if (nullptr == g_window)
        return;
    ANativeWindow_release(g_window);
    android::ANativeWindowCreator::Destroy(g_window);
    g_window = nullptr;
    ALOG(ANDROID_LOG_INFO, "anti-capture layer destroyed");
}

ANativeWindow *surface_window() {
    return g_window;
}

int surface_orientation() {
    return g_orientation;
}

void surface_refresh_display_info() {
    auto info = android::ANativeWindowCreator::GetDisplayInfo();
    g_orientation = info.orientation;
}

} // namespace hud
