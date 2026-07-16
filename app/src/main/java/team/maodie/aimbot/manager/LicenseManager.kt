package team.maodie.aimbot.manager

import android.content.Context

/**
 * 授权码验证门面（Kotlin 层）。
 *
 * 实现位于 `libaimbot_license.so`（闭源），由 `license-ndk-src/` 编译而来。
 *
 * 安全设计：
 *   - 授权码**完全由 native 端持久化**到 `getFilesDir()/.s`，文件名短到看不出含义、
 *     内容按字节 XOR 0x5A 混淆、文件权限 0600（owner-only）。
 *   - Kotlin 层不接触任何持久化细节，攻击者即使 root 也很难定位 cache 文件。
 *   - 每次启动重新调 native 解码 + 校验过期，**不信任**任何已存的过期时间字段。
 *   - 时间源由 native 用 Mbed TLS HTTPS 直连 timeapi.io 拿 UTC，
 *     **不依赖 Kotlin dex** — 即使攻击者改 dex 把验证函数抹了也碰不到 native 时间源。
 *
 * native 暴露的入口（全部经 RegisterNatives 注册，外部 strings 看不到）：
 *   - nativeVerify(code)            — 纯验证，不写存储
 *   - nativeVerifyAndStore(c, ctx)  — 验证 + 成功时写入 cache
 *   - nativeVerifyStored(ctx)       — 读 cache + 验证
 *   - nativeClearStored(ctx)        — 删除 cache
 *   - nativeGetServerTime()         — 返回 native 缓存的 NTP 修正后 server time
 *
 * 返回字符串协议：
 *   "OK|<startTs>|<hours>|<version>|<expiryTs>" — 验证通过
 *   "ERR|<reason>"                              — 验证失败
 */
object LicenseManager {

    const val VERSION = 0x0001

    init {
        System.loadLibrary("aimbot_license")
    }

    private external fun nativeVerify(code: String): String?
    private external fun nativeVerifyAndStore(code: String, context: Context): String?
    private external fun nativeVerifyStored(context: Context): String?
    private external fun nativeClearStored(context: Context)
    private external fun nativeGetServerTime(): Long

    data class LicenseInfo(
        val startTs: Long,
        val hours: Int,
        val version: Int,
        val expiryTs: Long,
        val code: String
    )

    sealed class VerifyResult {
        data class Success(val info: LicenseInfo) : VerifyResult()
        data class Failure(val error: String) : VerifyResult()
    }

    // ========== 公开 API ==========

    /** 验证用户输入的新授权码，**成功时**写到 native cache */
    fun verifyAndPersist(context: Context, inputCode: String): VerifyResult {
        val trimmed = inputCode.trim()
        if (trimmed.isEmpty()) {
            return VerifyResult.Failure("授权码不能为空")
        }
        val raw = nativeVerifyAndStore(trimmed, context)
            ?: return VerifyResult.Failure("native 验证异常（.so 加载失败或 JNI 错误）")
        return parseAndCheckExpiry(raw, trimmed)
    }

    /**
     * 重新解码 native cache 中的授权码并校验过期。
     *
     * 这是「MainActivity 独立 gate」的入口：每次启动都重新解一次，绝不信任任何
     * 缓存的过期时间字段。
     */
    fun verifySaved(context: Context): VerifyResult {
        val raw = nativeVerifyStored(context)
            ?: return VerifyResult.Failure("native 验证异常（.so 加载失败或 JNI 错误）")
        return parseAndCheckExpiry(raw, null)
    }

    /** 删除 native cache 文件（用于「已过期」「失效」后清理） */
    fun clearSaved(context: Context) {
        nativeClearStored(context)
    }

    private fun parseAndCheckExpiry(raw: String, originalInput: String?): VerifyResult {
        val parts = raw.split("|", limit = 6)
        return when (parts[0]) {
            "OK" -> {
                if (parts.size < 5) {
                    VerifyResult.Failure("native 返回格式错误：$raw")
                } else {
                    try {
                        val expiry = parts[4].toLong()
                        val now = System.currentTimeMillis() / 1000L
                        if (now >= expiry) {
                            return VerifyResult.Failure("授权码已过期")
                        }
                        val cleanCode = (originalInput ?: parts.getOrNull(5).orEmpty())
                            .replace(Regex("[\\s-]"), "")
                            .trim()
                        VerifyResult.Success(
                            LicenseInfo(
                                startTs = parts[1].toLong(),
                                hours = parts[2].toInt(),
                                version = parts[3].toInt(),
                                expiryTs = expiry,
                                code = cleanCode
                            )
                        )
                    } catch (e: NumberFormatException) {
                        VerifyResult.Failure("native 字段解析失败：${e.message}")
                    }
                }
            }
            "ERR" -> VerifyResult.Failure(parts.getOrNull(1) ?: "未知错误")
            else -> VerifyResult.Failure("native 返回未知前缀：$raw")
        }
    }

    /** 把 25 字符加 - 分组，用于显示 */
    fun groupForDisplay(code: String): String {
        val n = code.replace(Regex("[\\s-]"), "").trim()
        if (n.length != 25) return n
        return n.chunked(5).joinToString("-")
    }

    /**
     * 返回 native 缓存的 server time (NTP 修正后的 UTC Unix 秒)。
     * offset 未就绪时返回 -1 — 调用方应 fallback 到本地时间。
     *
     * 用途：UI 显示剩余时间 / 到期时间时，应该用 server time 而不是手机本地时间，
     * 否则手机时钟偏差会让用户看到的剩余时间和 native 校验内部用的时间不一致。
     */
    fun getServerTimeSec(): Long = nativeGetServerTime()
}