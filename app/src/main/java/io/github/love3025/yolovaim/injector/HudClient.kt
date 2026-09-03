package io.github.love3025.yolovaim.injector

/**
 * 防捕获 native HUD 的 IPC 客户端接口(改进方案.md §4.2)。
 *
 * 只有走 root daemon 的注入路径实现了它(RootInjectorClient)——HUD layer
 * 由 daemon 以 root 直连 SurfaceFlinger 创建,Shizuku / InputManager 路径
 * 没有这个通道,FloatService 拿到 null 就用 OverlayCanvasView 兜底。
 *
 * 除 hudOn() 外全部是 fire-and-forget('!' 前缀无回复路径):这些方法
 * 会出现在推理热路径上,不能阻塞等 daemon 回话。
 */
interface HudClient {

    /** 创建防捕获 layer。同步往返,只在连接建立时调一次。失败返回 false。 */
    fun hudOn(): Boolean

    /** 销毁 layer。幂等。 */
    fun hudOff()

    /** 单项显示开关。what: captureRange | fov | box | centerDot | inferInfo。 */
    fun hudToggle(what: String, on: Boolean)

    /**
     * 自检模式:开 = layer 只画洋红色检查图案(中心实心圆 + 大圆环),
     * 关 = 恢复正常元素。上层在采集帧里检索这个颜色判定防捕获是否生效
     * —— 用白色判据会和游戏自身的准星撞车,首版就栽在这上面。
     */
    fun hudCheck(on: Boolean)

    /** 采集空间宽高(镜像 MediaProjection 坐标系,旋转后要重发)。 */
    fun hudGeo(w: Int, h: Int)

    /** FOV 半径(px)。调用方负责值变才发。 */
    fun hudFov(r: Int)

    /** 截取范围半径(px)。 */
    fun hudRange(r: Int)

    /** 检测框批量更新。rects 为扁平 [x1,y1,x2,y2]*n,最多 16 框。 */
    fun hudBoxes(rects: IntArray)

    /**
     * 推理信息文字:8bit alpha 覆盖率掩码 + 前景/背景色(0xAARRGGBB)。
     *
     * 只传覆盖率、由 daemon 侧合成药丸底与文字颜色 —— 抗锯齿文字的
     * ARGB 位图逐行 RLE 会退化成每帧数千段(950x52 实测 5558 段 / 270
     * 条 IPC 行),每条都要独占 cmdLock 并挤掉注入命令。掩码是整块一次
     * 写入,系统调用从每帧 270 次降到 1 次。
     *
     * mask 长度须 >= w*h(packed,行主序),len 为实际有效字节数。
     */
    fun hudTextMask(w: Int, h: Int, fg: Int, bg: Int, mask: ByteArray, len: Int)
}
