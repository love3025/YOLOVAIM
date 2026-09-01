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

    /** 采集空间宽高(镜像 MediaProjection 坐标系,旋转后要重发)。 */
    fun hudGeo(w: Int, h: Int)

    /** FOV 半径(px)。调用方负责值变才发。 */
    fun hudFov(r: Int)

    /** 截取范围半径(px)。 */
    fun hudRange(r: Int)

    /** 检测框批量更新。rects 为扁平 [x1,y1,x2,y2]*n,最多 16 框。 */
    fun hudBoxes(rects: IntArray)

    /**
     * 推理信息文字位图。pixels 为 ARGB_8888 整行扫描的 0xAARRGGBB,
     * 透明像素由实现端(编码时)剔除。
     */
    fun hudTextBmp(w: Int, h: Int, pixels: IntArray)
}
