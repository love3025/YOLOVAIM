package team.maodie.aimbot;

interface IRemoteInjector {
    boolean init();
    void destroy();
    void tap(int x, int y);
    void swipe(int x1, int y1, int x2, int y2, int durationMs);
    void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH);
    void keepAlive();
    boolean isAvailable();
    void setResolution(int screenW, int screenH, int devW, int devH);
}