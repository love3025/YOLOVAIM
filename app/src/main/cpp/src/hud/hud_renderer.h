/*
 * hud_renderer.h — 防捕获 HUD 的状态存储与绘制。
 *
 * IPC 读线程(root_daemon 主循环)只调 set_* / text_* 系列更新状态:
 * 加锁 → 改状态 → 置脏 → 唤醒渲染线程,立即返回,绘制永不阻塞
 * 注入指令的解析(改进方案.md §4.2 性能约束)。
 *
 * 坐标系:所有 set_* 接口的坐标都在「采集空间」——即 MediaProjection
 * 输出帧的坐标系,与旧 OverlayCanvasView 的绘制坐标完全一致。旋转到
 * layer-stack 空间的变换在渲染时内部完成。
 */

#ifndef HUD_RENDERER_H // !HUD_RENDERER_H
#define HUD_RENDERER_H

#include <cstdint>

namespace hud {

// 启动:创建 layer + 渲染线程。幂等。
// 返回 0 成功;<0 失败(码同 surface_create,另加 -3 = 首帧锁定失败)。
int renderer_start();

// 停止:结束渲染线程 + 销毁 layer。幂等。
void renderer_stop();

// 当前采集空间宽高(默认取显示器信息,Kotlin 端会随后发 HUD_GEO 校准)。
void set_geo(int w, int h);

// 开关。what 取值:captureRange | fov | box | centerDot | inferInfo。
// 未知 what 忽略并打日志。
void set_toggle(const char *what, int on);

// 自检模式:开 = 只画洋红色检查图案(中心实心圆 + 大圆环),关 = 恢复
// 正常元素。用于上层在采集帧里检索该颜色,判定防捕获是否真的生效。
void set_check_mode(int on);

// FOV 半径 / 截取范围半径,px,采集空间。
void set_fov(int r);
void set_range(int r);

// 检测框批量更新。n 个框,xyxy 为扁平数组 [x1,y1,x2,y2]*n,采集空间。
// 上限 16 框(协议行长约束),超出截断。
void set_boxes(int n, const int *xyxy);

// 文字位图三段式:begin 分配清零 → run 逐段写 → end 提交触发重绘。
// 一段 run = 第 y 行从 x 起连续 len 个相同像素(argb 为 Android 的
// 0xAARRGGBB)。透明像素不必发送(begin 已清零)。
void text_begin(int w, int h);
void text_run(int y, int x, int len, uint32_t argb);
void text_end();

} // namespace hud

#endif // !HUD_RENDERER_H
