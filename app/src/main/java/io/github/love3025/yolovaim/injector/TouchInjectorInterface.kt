package io.github.love3025.yolovaim.injector

import io.github.love3025.yolovaim.model.TouchMethod

interface InjectorCallback {
    fun onConnected()
    fun onDisconnected()
    fun onError(msg: String)
}

interface TouchInjectorInterface {
    fun connect(callback: InjectorCallback)
    fun isConnected(): Boolean
    fun disconnect()

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    fun moveTo(x: Int, y: Int)
    fun lift()
    fun keepAlive()

    fun triggerDown(x: Int, y: Int)
    fun triggerUp()
    fun triggerTap(x: Int, y: Int, durationMs: Int)

    fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInTriggerZone(): Boolean
    fun setFireZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInFireZone(): Boolean

    /**
     * 一次取回开火区电平与点击数：`bit0` = 是否有手指在开火区，
     * `bit1..` = 自上次调用以来的点击(上升沿)次数，取走即清零。
     * **注入层不支持时返回 -1**，调用方需回退到 [isFingerInFireZone]。
     *
     * 为什么要点击数：isFingerInFireZone() 是电平查询，应用按推理帧率
     * (30-60Hz)采样，而注入层看到的是 120-240Hz 的真实触摸事件。短于一个
     * 帧间隔的点击会被整帧漏掉，且漏与不漏取决于点击与推理帧的相位。
     *
     * 为什么打包成一个 int：推理循环每帧都要这两个值，拆成两次调用就是每帧
     * 多一次阻塞式 IPC 往返，正是 4ee9e9e 刚从热路径上消除掉的那类浪费。
     */
    fun consumeFireState(): Int
    fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInJoystickZone(): Boolean
    fun liftJoystickFinger(): Boolean

    fun setInputMethod(method: TouchMethod)
    fun initRemote(): Boolean
    fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int)
    fun setOrientationConfig(landscapeStart: Boolean)

    fun startGeteventListener()
    fun stopGeteventListener()

    fun blockPhysicalTouch()
    fun unblockPhysicalTouch()

    fun destroyRemote()

    fun queryDeviceAbs(devicePath: String, axis: Int): IntArray
    fun findTouchDevice(): String?
}
