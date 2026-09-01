/*
 * hud_surface.h — 防捕获 HUD layer 的生命周期管理(多 layer 版)。
 *
 * HUD 用两个 layer(几何内容方块 + 推理信息文字条)代替一个大全屏 layer:
 * 每帧更新的 buffer 越小,清屏写入/提交/合成越便宜 —— 全屏 2400x1080
 * layer 按推理帧率整屏重写在真机上会造成游戏卡顿(实测,方案 §7 帧率
 * 验收项踩的坑)。layer 尺寸/位置烙定于创建时,变化由渲染线程销毁重建。
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
