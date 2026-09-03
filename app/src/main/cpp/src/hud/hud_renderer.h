/*
 * hud_renderer.h — 防捕获 HUD 的状态存储与绘制。
 *
 * IPC 读线程(root_daemon 主循环)只调 set_* 系列更新状态:加锁 → 改状态
 * → 置脏 → 唤醒渲染线程,立即返回,绘制永不阻塞注入指令的解析
 * (改进方案.md §4.2 性能约束)。
 *
 * 坐标系:所有 set_* 接口的坐标都在「采集空间」——即 MediaProjection
 * 输出帧的坐标系,与旧 OverlayCanvasView 的绘制坐标完全一致。layer-stack
 * 空间跟随屏幕旋转,与采集坐标恒等,不需要任何旋转变换。
 */

#ifndef HUD_RENDERER_H // !HUD_RENDERER_H
#define HUD_RENDERER_H

#include <cstddef>
#include <cstdint>

namespace hud {

// 启动:创建渲染线程(layer 按需创建)。幂等。
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

// FOV 半径 / 截取范围半径,px,采集空间。开关关闭时也存值(只是不重绘)
// —— 否则隐藏期间的变化会丢,再打开时画的是过期半径。
void set_fov(int r);
void set_range(int r);

// 检测框批量更新。n 个框,xyxy 为扁平数组 [x1,y1,x2,y2]*n,采集空间。
// 上限 16 框(协议行长约束),超出截断。
void set_boxes(int n, const int *xyxy);

// 推理信息文字:8bit alpha 覆盖率掩码(w*h,packed,行主序)+ 前景/背景色
// (Android 0xAARRGGBB)。一次调用整块提交 —— 旧的 begin/run*/end 逐段
// RLE 协议在抗锯齿文字上退化成每帧数千段、数百条 IPC 行,是防录屏开启
// 后卡顿的主因之一(实测 950x52 文字 = 5558 段/帧)。
void set_text_mask(int w, int h, uint32_t fg, uint32_t bg,
                   const uint8_t *mask, size_t n);

} // namespace hud

#endif // !HUD_RENDERER_H
