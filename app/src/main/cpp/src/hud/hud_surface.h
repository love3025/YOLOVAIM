/*
 * hud_surface.h — 防捕获 HUD layer 的生命周期管理(多 layer 版)。
 *
 * HUD 用两个 layer:几何元素(全屏尺寸,只在旋转时重建)+ 推理信息药丸
 * (顶部小条,尺寸按 128px 桶量化)。layer 尺寸/位置烙定于创建时,变化
 * 只能靠销毁重建 —— 所以尺寸必须**与每帧内容无关**,否则检测框一动就
 * 重建全屏 layer(3 个 gralloc buffer + SF 事务),那比全屏清屏更贵。
 * 每帧写入量由 hud_renderer 的脏矩形跟踪控制,不靠缩小 layer。
 */

#ifndef HUD_SURFACE_H // !HUD_SURFACE_H
#define HUD_SURFACE_H

#include <android/native_window.h>

namespace hud {

struct HudSurface {
    ANativeWindow *win = nullptr;
    int w = 0; // layer 尺寸(layer-stack 空间)
    int h = 0;
    int x = 0; // layer 位置(采集空间坐标,与 layer-stack 恒等)
    int y = 0;
};

// libgui 私有符号是否全部解析成功(含 setPosition)。false = 本 ROM 不
// 支持防捕获机制,上层走兜底。
bool surface_symbols_ready();

// 创建带防捕获标记、指定尺寸/位置的 layer(z=100,与 anti-capture-demo
// 实测参数一致)。返回 0 成功;<0 失败(-1 符号,-2 创建失败)。
int surface_create(HudSurface *out, int w, int h, int x, int y, const char *name);

// 销毁 layer。幂等,清空字段。
void surface_destroy(HudSurface *s);

} // namespace hud

#endif // !HUD_SURFACE_H
