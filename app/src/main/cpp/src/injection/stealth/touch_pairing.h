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
 * 【盐不进 git】本仓库有远程,盐一旦提交,任何有读权限的人就能算出通道密钥。
 * 真实盐写在 local.properties 的 stealth.pairSalt=...,由 app/build.gradle.kts
 * 经 -DTOUCH_PAIR_SALT="..." 注入 CMake;没配盐直接构建会被 CMake 拦下
 * (CI 用 -Pstealth.allowPlaceholder=true 显式豁免,产物里 Stealth 不可用)。
 *
 * 同步到 KPM 侧:用 scripts/sync_touch_pairing.sh,它会把本文件渲染成
 * 「盐已展开」的完整版写进 kpm-src/kpms/inputprobe/,并校验包名/通道号一致。
 * ===========================================================================
 */

#define TOUCH_TARGET_PACKAGE "io.github.love3025.yolovaim"

/* 真实值由构建注入(-DTOUCH_PAIR_SALT=...);这里是占位符。
 * CMake 会检查占位符未被替换的情况并拒绝构建。 */
#ifndef TOUCH_PAIR_SALT
#define TOUCH_PAIR_SALT      "PLACEHOLDER-configure-stealth.pairSalt"
#endif

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
