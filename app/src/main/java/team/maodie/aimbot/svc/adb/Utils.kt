package team.maodie.aimbot.svc.adb

@Suppress("NOTHING_TO_INLINE", "FunctionName")
inline fun <T> unsafeLazy(noinline initializer: () -> T): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { initializer() }
