package io.github.love3025.yolovaim.model

enum class TouchMethod {
    UINPUT,
    INPUT_MANAGER,
    /** 内核 KPM 无痕注入:需 root + 设备已装同盐编译的 inputprobe.kpm */
    STEALTH
}
