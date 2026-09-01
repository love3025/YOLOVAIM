/*
 * hud_surface.cpp — 防捕获 HUD layer 的生命周期管理(多 layer 版)。
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

bool surface_symbols_ready() {
    return android::ANativeWindowCreator::SymbolsReady();
}

int surface_create(HudSurface *out, int w, int h, int x, int y, const char *name) {
    if (out->win)
        return 0;
    if (w <= 0 || h <= 0)
        return -3;

    if (!android::ANativeWindowCreator::SymbolsReady()) {
        ALOG(ANDROID_LOG_ERROR, "symbols not resolved, anti-capture unsupported on this ROM");
        return -1;
    }

    // z=100:压过应用窗口和应用型悬浮窗,又不与系统关键层抢位置,
    // 与 demo PoC 实测参数一致;skipScrenshot=true 打防捕获标记。
    ANativeWindow *win = android::ANativeWindowCreator::Create(name, w, h, true, 100,
                                                               static_cast<float>(x),
                                                               static_cast<float>(y));
    if (nullptr == win) {
        ALOG(ANDROID_LOG_ERROR, "layer/window creation failed (%s %dx%d@%d,%d)", name, w, h, x, y);
        return -2;
    }
    ANativeWindow_acquire(win);
    out->win = win;
    out->w = w;
    out->h = h;
    out->x = x;
    out->y = y;
    ALOG(ANDROID_LOG_INFO, "anti-capture layer created (%s %dx%d@%d,%d)", name, w, h, x, y);
    return 0;
}

void surface_destroy(HudSurface *s) {
    if (nullptr == s->win)
        return;
    ANativeWindow_release(s->win);
    android::ANativeWindowCreator::Destroy(s->win);
    ALOG(ANDROID_LOG_INFO, "anti-capture layer destroyed (%dx%d@%d,%d)", s->w, s->h, s->x, s->y);
    s->win = nullptr;
    s->w = s->h = s->x = s->y = 0;
}

} // namespace hud
