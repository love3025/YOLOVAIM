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
     * 取走自上次调用以来开火区被按下的次数（上升沿计数）并清零。
     *
     * isFingerInFireZone() 是电平查询：应用按推理帧率(30-60Hz)采样，而注入层
     * 看到的是 120-240Hz 的真实触摸事件，短于一个帧间隔的点击会被整帧漏掉，
     * 且漏与不漏取决于点击与推理帧的相位。半自动连点必须靠这个计数才能按
     * 「打了几枪」而不是「按了多久」来计量。
     */
    fun consumeFireTaps(): Int
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
