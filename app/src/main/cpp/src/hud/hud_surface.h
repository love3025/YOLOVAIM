/*
 * hud_surface.h — 防捕获 HUD layer 的生命周期管理。
 *
 * 只负责 layer 本身:符号探测、创建(带防捕获标记)、销毁、显示器信息。
 * 画什么、什么时候画是 hud_renderer 的事。
 *
 * layer 尺寸取当前显示器 layer-stack 空间(自然方向,竖屏手机上即竖屏
 * 尺寸),不随屏幕旋转重建 —— 旋转由 hud_renderer 的坐标变换吸收。
 */

#ifndef HUD_SURFACE_H // !HUD_SURFACE_H
#define HUD_SURFACE_H

#include <android/native_window.h>

namespace hud {

// libgui 私有符号是否全部解析成功。false = 本 ROM 不支持防捕获机制
// (雪花内核 v1 静默失败的形态,这里显式可查)。
bool surface_symbols_ready();

// 创建防捕获 layer(z=100,压过应用窗口又不与系统关键层抢位置,
// 与 anti-capture-demo 实测通过的参数一致)。
// 返回 0 成功;<0 失败,细节走 logcat/stderr:
//   -1 符号解析失败  -2 layer/Surface 创建失败
int surface_create();

// 销毁 layer。幂等。
void surface_destroy();

// 当前 layer 的窗口。未创建时为 nullptr。
ANativeWindow *surface_window();

// 最近一次查询到的显示器方向(0/1/2/3,对应 SurfaceFlinger Rotation)。
// surface_create / surface_refresh_display_info 时更新。
int surface_orientation();

// 重新查询显示器方向(屏幕旋转后调用)。
void surface_refresh_display_info();

} // namespace hud

#endif // !HUD_SURFACE_H
