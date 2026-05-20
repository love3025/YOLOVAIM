package team.maodie.aimbot;

interface IRemoteInjector {
    boolean init();
    void linkToDeath(in IBinder token);
    void destroy();
    void tap(int x, int y);
    void swipe(int x1, int y1, int x2, int y2, int durationMs);
    void moveTo(int x, int y);
    void lift();
    void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH);
    void keepAlive();
    boolean isAvailable();
    void setResolution(int screenW, int screenH, int devW, int devH);
    void setOrientationConfig(boolean landscapeStart);
    void startGeteventListener();
    void stopGeteventListener();
    void blockPhysicalTouch();
    void unblockPhysicalTouch();
    void triggerDown(int x, int y);
    void triggerUp();
    void triggerTap(int x, int y, int durationMs);
    void setTriggerZone(int left, int top, int right, int bottom);
    boolean isFingerInTriggerZone();
}