package io.github.love3025.yolovaim;

/**
 * Shizuku fallback injection interface (the root daemon is the default path).
 *
 * Methods marked `oneway` are the void injection calls the inference loop makes
 * every frame. Without it each one is a blocking cross-process transaction: the
 * loop issues the transaction and sleeps until the remote has finished writing
 * to uinput, several times per frame, on the critical path.
 *
 * Two rules constrain which methods may carry it, and both are load-bearing:
 *
 *  1. Only void methods. A method with a return value cannot be oneway.
 *
 *  2. The injection calls that must stay mutually ordered are oneway *as a
 *     group*. Oneway transactions to one binder node drain from a single FIFO
 *     async queue, so marking all of them preserves their relative order; a
 *     synchronous call, by contrast, is served by a free pool thread and can
 *     overtake a queued oneway. Marking only some of swipe/moveTo/lift would
 *     therefore let a pointer-down overtake a pending lift and strand the
 *     injected finger.
 *
 * tap() and triggerTap() stay synchronous on purpose: both sleep inside the
 * remote (8ms and durationMs respectively), and a sleeper on the shared async
 * queue would hold up every moveTo queued behind it. Neither runs on the
 * inference thread — TouchService dispatches trigger taps to its own executor.
 */
interface IRemoteInjector {
    boolean init();
    void setInputMethod(int method);
    void linkToDeath(in IBinder token);
    void destroy();
    void tap(int x, int y);
    oneway void swipe(int x1, int y1, int x2, int y2, int durationMs);
    oneway void moveTo(int x, int y);
    oneway void lift();
    void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH);
    void keepAlive();
    boolean isAvailable();
    void setResolution(int screenW, int screenH, int devW, int devH);
    void setOrientationConfig(boolean landscapeStart);
    void startGeteventListener();
    void stopGeteventListener();
    void blockPhysicalTouch();
    void unblockPhysicalTouch();
    oneway void triggerDown(int x, int y);
    oneway void triggerUp();
    void triggerTap(int x, int y, int durationMs);
    void setTriggerZone(int left, int top, int right, int bottom);
    boolean isFingerInTriggerZone();
    void setFireZone(int left, int top, int right, int bottom);
    boolean isFingerInFireZone();
    /** 一次调用取回开火区电平与点击数：bit0 = 电平，bit1.. = 点击次数。 */
    int consumeFireState();
    void setJoystickZone(int left, int top, int right, int bottom);
    boolean isFingerInJoystickZone();
    boolean liftJoystickFinger();
}