/*
 * hud_surface.h — 防捕获 HUD layer 的生命周期管理。
 *
 * 只负责 layer 本身:符号探测、创建(带防捕获标记)、销毁、当前尺寸。
 * 画什么、什么时候画是 hud_renderer 的事。
 *
 * 尺寸说明:创建时取当前显示器 layer-stack 空间的尺寸(跟随屏幕旋转,
 * 真机实证见 hud_renderer.cpp 文件头)。layer 尺寸烙定于创建时 ——
 * 旋转后需要调用方(render 线程)销毁重建。
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

// 当前 layer 创建时的尺寸(layer-stack 空间,跟随屏幕方向)。
// 未创建时返回 0x0。
void surface_layer_dims(int *w, int *h);

} // namespace hud

#endif // !HUD_SURFACE_H
