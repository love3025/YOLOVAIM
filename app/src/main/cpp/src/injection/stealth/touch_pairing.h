#ifndef TOUCH_PAIRING_H
#define TOUCH_PAIRING_H
/* ===========================================================================
 * 一对一绑定配置 —— KPM 侧与接入库侧必须使用【密钥一致】的本文件。
 * 本文件有两份副本:
 *   1) 本文件 (app 侧,root_daemon 编 touchc.cpp 用)
 *   2) 无痕触摸源码/kpm-src/kpms/inputprobe/touch_pairing.h (内核模块用)
 *
 * 通道密钥 = FNV-1a64(TOUCH_TARGET_PACKAGE + TOUCH_PAIR_SALT),编译期算好、库
 * 每次调用自动带上。只有用【相同 包名+盐】编译出的 app 能算出同一把钥匙;
 * 拿不到盐的其它进程调用该 syscall 会穿透成原始系统调用 —— 既用不了,也探测
 * 不到通道存在(ping 不回)。无需运行时输入任何东西。
 *
 * 【盐直接写在源码里】(决策 2026-09-03):盐是编译期常量,本来就会躺在每个
 * 分发出去的 APK 的 libroot_daemon.so 里(strings 可提取),仓库级保密没有
 * 实际意义;通道本身还要求 root 调用方,威胁模型里"偷盐"换不来什么 ——
 * 有 root 直接走 uinput 即可,想要无痕自编 KPM 即可。盐在源码里的价值是
 * 让任何拿到源码的人直接构建出可配对的 app,无需配置任何东西。
 *
 * 同步到 KPM 侧:直接拷贝本文件到 kpm-src/kpms/inputprobe/(两份一字不差),
 * scripts/sync_touch_pairing.sh 做的就是这件事并校验一致性。
 * ===========================================================================
 */

#define TOUCH_TARGET_PACKAGE "io.github.love3025.yolovaim"
#define TOUCH_PAIR_SALT      "6d8076327d3f2384c9dbb78113b273a0"

/* syscall 通道号:冷门号(默认 18 = __NR_lookup_dcookie),库与 KPM 必须一致。
 * 想更隐蔽可换其它极少用到的号,两侧同步改即可。 */
#define TOUCH_SC_NR 18

/* FNV-1a 64 位;C / C++ 均可编译,freestanding(不产生 memcpy/memset)。 */
static inline unsigned long long touch_pair_key(void)
{
    const char *s = TOUCH_TARGET_PACKAGE TOUCH_PAIR_SALT;
    unsigned long long h = 1469598103934665603ULL; /* offset basis */
    while (*s) {
        h ^= (unsigned char)*s++;
        h *= 1099511628211ULL;                      /* FNV prime */
    }
    if (!h) h = 0x9E3779B97F4A7C15ULL;              /* 0 保留给“非本通道”,规避之 */
    return h;
}

#endif /* TOUCH_PAIRING_H */
