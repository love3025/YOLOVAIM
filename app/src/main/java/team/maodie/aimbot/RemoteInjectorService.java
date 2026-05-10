package team.maodie.aimbot;

import android.annotation.SuppressLint;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import team.maodie.aimbot.IRemoteInjector;

public class RemoteInjectorService extends IRemoteInjector.Stub {
    private static final String TAG = "RemoteInjector";
    private static final int BG_ID = 10;
    private static final int INJECT_MODE_ASYNC = 0;

    private Method injectMethod;
    private Object inputManager;
    private static int uinputFd = -1;
    private static int dev_abs_max_x = 21199;
    private static int dev_abs_max_y = 29999;
    private static int screen_w = 2120;
    private static int screen_h = 3000;

    public volatile boolean available = false;
    long bgDownTime;
    private int lastTapId = 6;
    private int drawingPointerId = -1;
    private boolean pointerDown = false;

    public static RemoteInjectorService instance;

    public void setResolution(int sw, int sh, int dw, int dh) {
        screen_w = sw;
        screen_h = sh;
        dev_abs_max_x = dw;
        dev_abs_max_y = dh;
        setDeviceResolution(dw, dh);
        setScreenResolution(sw, sh);
    }

    public void setOrientationConfig(boolean landscapeStart) {
        setLandscapeStart(landscapeStart ? 1 : 0);
    }

    public void startGeteventListener() {
        startGeteventListenerNative();
    }

    public void stopGeteventListener() {
        stopGeteventListenerNative();
    }

    private MotionEvent.PointerProperties ptr(int id) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return p;
    }

    private MotionEvent.PointerCoords coord(float x, float y) {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x; c.y = y;
        return c;
    }

    public void onCreate() {
        instance = this;
        Log.d(TAG, "RemoteInjectorService onCreate, pid=" + Process.myPid());
    }

    private int openUinput() {
        return openUinputNative();
    }

    private void closeUinput() {
        if (uinputFd >= 0) {
            closeUinputNative();
            uinputFd = -1;
        }
    }

    @SuppressLint("PrivateApi")
    public boolean init() {
        if (available) return true;
        Log.d(TAG, "init: starting, pid=" + Process.myPid());

        try {
            uinputFd = openUinputNative();
            Log.d(TAG, "init: openUinputNative returned fd=" + uinputFd);
            if (uinputFd >= 0) {
                bgDownTime = SystemClock.uptimeMillis();
                available = true;
                Log.d(TAG, "init: RemoteInjectorService ready with uinput, pid=" + Process.myPid());
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "init: uinput exception: " + e.getMessage());
        }

        try {
            Method getInstance = android.hardware.input.InputManager.class.getMethod("getInstance");
            android.hardware.input.InputManager inputMan = (android.hardware.input.InputManager) getInstance.invoke(null);
            Log.d(TAG, "init: inputManager=" + inputMan);

            Method injectInputEvent = android.hardware.input.InputManager.class.getMethod(
                "injectInputEvent", InputEvent.class, int.class);
            Log.d(TAG, "init: injectMethod=" + injectInputEvent);

            bgDownTime = SystemClock.uptimeMillis();
            MotionEvent bgDown = MotionEvent.obtain(bgDownTime, bgDownTime, MotionEvent.ACTION_DOWN, 1,
                new MotionEvent.PointerProperties[]{ptr(BG_ID)}, new MotionEvent.PointerCoords[]{coord(5f, 5f)},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            Object result = injectInputEvent.invoke(inputMan, bgDown, INJECT_MODE_ASYNC);
            Log.d(TAG, "init: injectInputEvent result=" + result);
            bgDown.recycle();

            inputManager = inputMan;
            injectMethod = injectInputEvent;
            bgDownTime = SystemClock.uptimeMillis();
            available = true;
            Log.d(TAG, "RemoteInjector ready via injectInputEvent, pid=" + Process.myPid());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init: injectInputEvent failed: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    public void keepAlive() {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputSendMove(uinputFd, 5, 5, BG_ID);
        } else if (inputManager != null && injectMethod != null) {
            try {
                MotionEvent m = MotionEvent.obtain(bgDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1,
                    new MotionEvent.PointerProperties[]{ptr(BG_ID)}, new MotionEvent.PointerCoords[]{coord(5f, 5f)},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, m, INJECT_MODE_ASYNC);
                m.recycle();
            } catch (Exception e) { Log.e(TAG, "keepAlive: " + e.getMessage()); }
        }
    }

    public void tap(int x, int y) throws android.os.RemoteException {
        if (!available) return;
        try {
            long now = SystemClock.uptimeMillis();
            int tapId = 7 + ((int)(Math.random() * 3));
            if (tapId == lastTapId) tapId = (tapId + 1) % 10 + 7;
            lastTapId = tapId;

            if (uinputFd >= 0) {
                uinputSendDown(uinputFd, x, y, tapId);
                try { Thread.sleep(8); } catch (InterruptedException e) {}
                uinputSendUp(uinputFd, tapId);
            } else {
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                    new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(tapId)},
                    new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x, y)},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                MotionEvent up = MotionEvent.obtain(bgDownTime, now + 8, MotionEvent.ACTION_POINTER_UP | shift, 2,
                    new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(tapId)},
                    new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x, y)},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                down.recycle(); up.recycle();
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws android.os.RemoteException {
        if (!available) return;
        try {
            long now = SystemClock.uptimeMillis();
            int tapId = 7 + ((int)(Math.random() * 3));
            if (tapId == lastTapId) tapId = (tapId + 1) % 10 + 7;
            lastTapId = tapId;

            if (uinputFd >= 0) {
                if (!pointerDown && x1 == x2 && y1 == y2) {
                    drawingPointerId = tapId;
                    uinputSendDown(uinputFd, x1, y1, drawingPointerId);
                    pointerDown = true;
                } else if (pointerDown) {
                    uinputSendMove(uinputFd, x2, y2, drawingPointerId);
                } else {
                    uinputSendDown(uinputFd, x1, y1, tapId);
                    try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
                    uinputSendMove(uinputFd, x2, y2, tapId);
                    uinputSendUp(uinputFd, tapId);
                }
            } else {
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                if (!pointerDown && x1 == x2 && y1 == y2) {
                    drawingPointerId = tapId;
                    pointerDown = true;
                    MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(drawingPointerId)},
                        new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x1, y1)},
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                    down.recycle();
                } else if (pointerDown) {
                    MotionEvent move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                        new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(drawingPointerId)},
                        new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x2, y2)},
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                    move.recycle();
                } else {
                    MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x1, y1)},
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    MotionEvent move = MotionEvent.obtain(bgDownTime, now + durationMs, MotionEvent.ACTION_MOVE, 2,
                        new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x2, y2)},
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    MotionEvent up = MotionEvent.obtain(bgDownTime, now + durationMs + 4, MotionEvent.ACTION_POINTER_UP | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x2, y2)},
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                    injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                    injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                    down.recycle(); move.recycle(); up.recycle();
                }
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void moveTo(int x, int y) throws android.os.RemoteException {
        if (!available || !pointerDown) return;
        try {
            if (uinputFd >= 0) {
                uinputSendMove(uinputFd, x, y, drawingPointerId);
            } else {
                long now = SystemClock.uptimeMillis();
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                MotionEvent move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                    new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(drawingPointerId)},
                    new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(x, y)},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                move.recycle();
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void lift() throws android.os.RemoteException {
        if (!available || !pointerDown) return;
        try {
            if (uinputFd >= 0) {
                uinputSendUp(uinputFd, drawingPointerId);
            } else {
                long now = SystemClock.uptimeMillis();
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                MotionEvent up = MotionEvent.obtain(bgDownTime, now + 4, MotionEvent.ACTION_POINTER_UP | shift, 2,
                    new MotionEvent.PointerProperties[]{ptr(BG_ID), ptr(drawingPointerId)},
                    new MotionEvent.PointerCoords[]{coord(5f, 5f), coord(5f, 5f)},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                up.recycle();
            }
            pointerDown = false;
            drawingPointerId = -1;
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH) throws android.os.RemoteException {
        if (!available) return;
        double dx = targetX - centerX;
        double dy = targetY - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1) return;
        int duration = Math.min(50, (int) (dist / speed));
        swipe(centerX, centerY, centerX + (int)dx, centerY + (int)dy, duration);
    }

    public void destroy() {
        available = false;
        closeUinput();
        inputManager = null;
        injectMethod = null;
    }

    @Override
    public boolean isAvailable() { return available; }

    private static native int openUinputNative();
    private static native void closeUinputNative();
    private static native void uinputSendDown(int fd, int x, int y, int pointerId);
    private static native void uinputSendMove(int fd, int x, int y, int pointerId);
    private static native void uinputSendUp(int fd, int pointerId);
    private static native void setDeviceResolution(int devW, int devH);
    private static native void setScreenResolution(int screenW, int screenH);
    private static native void setLandscapeStart(int isLandscape);
    private static native void startGeteventListenerNative();
    private static native void stopGeteventListenerNative();

    static {
        try {
            System.loadLibrary("uinput_inject");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load uinput_inject library: " + e.getMessage());
        }
    }
}