package io.github.love3025.yolovaim.injector;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;
import io.github.love3025.yolovaim.IRemoteInjector;
import io.github.love3025.yolovaim.model.TouchMethod;
import io.github.love3025.yolovaim.service.RemoteInjectorService;

public class ShizukuInjectorClient implements TouchInjectorInterface {
    private static final String TAG = "ShizukuInjector";
    private static final String LAT_TAG = "YolovaimLatency";
    private static final long CONNECT_TIMEOUT_MS = 10000;

    private final Context context;
    private volatile boolean connected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IRemoteInjector remoteService;
    private InjectorCallback pendingCallback;
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 0x4D49;
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
        new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return;
                Shizuku.removeRequestPermissionResultListener(this);
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Shizuku permission granted via request, retrying connect");
                    if (pendingCallback != null) {
                        InjectorCallback cb = pendingCallback;
                        pendingCallback = null;
                        connect(cb);
                    }
                } else {
                    Log.e(TAG, "Shizuku permission denied by user");
                    if (pendingCallback != null) {
                        pendingCallback.onError("Shizuku permission denied");
                        pendingCallback = null;
                    }
                }
            }
        };

    public ShizukuInjectorClient(Context context) {
        this.context = context;
    }

    @SuppressLint("PrivateApi")
    public void connect(InjectorCallback callback) {
        Log.d(TAG, "Attempting Shizuku connection...");

        int permResult = Shizuku.checkSelfPermission();
        Log.d(TAG, "Shizuku permission check: " + permResult + " (granted=" + android.content.pm.PackageManager.PERMISSION_GRANTED + ")");
        boolean pingOk = Shizuku.pingBinder();
        Log.d(TAG, "Shizuku pingBinder: " + pingOk);

        if (!pingOk) {
            Log.e(TAG, "Shizuku not running");
            callback.onError("Shizuku not running");
            return;
        }

        if (permResult != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku permission not granted: " + permResult + ", requesting...");
            pendingCallback = callback;
            try {
                Shizuku.addRequestPermissionResultListener(permissionListener);
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
            } catch (Exception e) {
                Log.e(TAG, "requestPermission failed: " + e.getMessage());
                Shizuku.removeRequestPermissionResultListener(permissionListener);
                pendingCallback = null;
                callback.onError("Shizuku permission request failed: " + e.getMessage());
            }
            return;
        }

        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(context, RemoteInjectorService.class))
                .daemon(false)
                .processNameSuffix("injector")
                .debuggable(true)
                .version(1);

            android.content.ServiceConnection conn = new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.d(TAG, "onServiceConnected called! service=" + service);
                    connected = true;
                    remoteService = IRemoteInjector.Stub.asInterface(service);
                    Log.d(TAG, "remoteService=" + remoteService);
                    callback.onConnected();
                    // Note: initRemote() is now called explicitly by FloatService after setResolution
                    // Do NOT call init() here - it would open uinput before resolution is set
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.w(TAG, "Shizuku service disconnected");
                    connected = false;
                    remoteService = null;
                    callback.onDisconnected();
                }
            };

            Log.d(TAG, "Calling Shizuku.bindUserService...");
            Shizuku.bindUserService(args, conn);
            Log.d(TAG, "bindUserService called, waiting for callback...");

            mainHandler.postDelayed(() -> {
                if (!connected) {
                    Log.e(TAG, "Shizuku connection timeout after 10s, falling back to inline");
                    Log.e(TAG, "connected=$connected remoteService=${remoteService != null}");
                    connected = false;
                    callback.onError("Connection timeout");
                }
            }, CONNECT_TIMEOUT_MS);

        } catch (Exception e) {
            Log.e(TAG, "connect error: " + e.getMessage());
            e.printStackTrace();
            callback.onError("Connect error: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        boolean result = connected && remoteService != null;
        if (!result) {
            Log.d(TAG, "isConnected: false (connected=$connected, remoteService=${remoteService != null})");
        }
        return result;
    }

    public void tap(int x, int y) {
        if (remoteService != null) {
            try {
                long t0 = System.nanoTime();
                remoteService.tap(x, y);
                logIfSlow("tap", t0);
            } catch (Exception e) {
                Log.e(TAG, "tap error: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "tap called but remoteService is null");
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (remoteService != null) {
            try {
                long t0 = System.nanoTime();
                remoteService.swipe(x1, y1, x2, y2, durationMs);
                logIfSlow("swipe", t0);
            } catch (Exception e) { Log.e(TAG, "swipe: " + e.getMessage()); }
        }
    }

    public void moveTo(int x, int y) {
        if (remoteService != null) {
            try {
                long t0 = System.nanoTime();
                remoteService.moveTo(x, y);
                logIfSlow("moveTo", t0);
            } catch (Exception e) { Log.e(TAG, "moveTo: " + e.getMessage()); }
        }
    }

    public void lift() {
        if (remoteService != null) {
            try {
                long t0 = System.nanoTime();
                remoteService.lift();
                logIfSlow("lift", t0);
            } catch (Exception e) { Log.e(TAG, "lift: " + e.getMessage()); }
        }
    }

    private static void logIfSlow(String op, long t0Ns) {
        double dtMs = (System.nanoTime() - t0Ns) / 1e6;
        if (dtMs > 0.5) {
            Log.d(LAT_TAG, String.format(java.util.Locale.US, "ShizukuIPC %s = %.2fms", op, dtMs));
        }
    }

    public void keepAlive() {
        if (remoteService != null) {
            try { remoteService.keepAlive(); } catch (Exception e) { Log.e(TAG, "keepAlive: " + e.getMessage()); }
        }
    }

    public void triggerDown(int x, int y) {
        if (remoteService != null) {
            try { remoteService.triggerDown(x, y); } catch (Exception e) { Log.e(TAG, "triggerDown: " + e.getMessage()); }
        }
    }

    public void triggerUp() {
        if (remoteService != null) {
            try { remoteService.triggerUp(); } catch (Exception e) { Log.e(TAG, "triggerUp: " + e.getMessage()); }
        }
    }

    public void triggerTap(int x, int y, int durationMs) {
        if (remoteService != null) {
            try { remoteService.triggerTap(x, y, durationMs); } catch (Exception e) { Log.e(TAG, "triggerTap: " + e.getMessage()); }
        }
    }

    public void setTriggerZone(int left, int top, int right, int bottom) {
        if (remoteService != null) {
            try { remoteService.setTriggerZone(left, top, right, bottom); } catch (Exception e) { Log.e(TAG, "setTriggerZone: " + e.getMessage()); }
        }
    }

    public boolean isFingerInTriggerZone() {
        if (remoteService != null) {
            try { return remoteService.isFingerInTriggerZone(); } catch (Exception e) { Log.e(TAG, "isFingerInTriggerZone: " + e.getMessage()); }
        }
        return false;
    }

    public void setFireZone(int left, int top, int right, int bottom) {
        if (remoteService != null) {
            try { remoteService.setFireZone(left, top, right, bottom); } catch (Exception e) { Log.e(TAG, "setFireZone: " + e.getMessage()); }
        }
    }

    public boolean isFingerInFireZone() {
        if (remoteService != null) {
            try { return remoteService.isFingerInFireZone(); } catch (Exception e) { Log.e(TAG, "isFingerInFireZone: " + e.getMessage()); }
        }
        return false;
    }

    public void setJoystickZone(int left, int top, int right, int bottom) {
        if (remoteService != null) {
            try { remoteService.setJoystickZone(left, top, right, bottom); } catch (Exception e) { Log.e(TAG, "setJoystickZone: " + e.getMessage()); }
        }
    }

    public boolean isFingerInJoystickZone() {
        if (remoteService != null) {
            try { return remoteService.isFingerInJoystickZone(); } catch (Exception e) { Log.e(TAG, "isFingerInJoystickZone: " + e.getMessage()); }
        }
        return false;
    }

    public boolean liftJoystickFinger() {
        if (remoteService != null) {
            try { return remoteService.liftJoystickFinger(); } catch (Exception e) { Log.e(TAG, "liftJoystickFinger: " + e.getMessage()); }
        }
        return false;
    }

    public void setInputMethod(TouchMethod method) {
        if (remoteService != null) {
            try {
                remoteService.setInputMethod(method.ordinal());
                Log.d(TAG, "setInputMethod: " + method);
            } catch (Exception e) {
                Log.e(TAG, "setInputMethod: " + e.getMessage());
            }
        }
    }

    public boolean initRemote() {
        if (remoteService != null) {
            try {
                boolean ok = remoteService.init();
                if (ok) {
                    // Register death recipient so service cleans up if we're killed
                    remoteService.linkToDeath(new android.os.Binder());
                }
                return ok;
            } catch (Exception e) {
                Log.e(TAG, "initRemote error: " + e.getMessage());
            }
        }
        return false;
    }

    public void setResolution(int screenW, int screenH, int devW, int devH) {
        if (remoteService != null) {
            try {
                remoteService.setResolution(screenW, screenH, devW, devH);
                Log.d(TAG, "setResolution: screen=" + screenW + "x" + screenH + " device=" + devW + "x" + devH);
            } catch (Exception e) {
                Log.e(TAG, "setResolution: " + e.getMessage());
            }
        }
    }

    public void setOrientationConfig(boolean landscapeStart) {
        if (remoteService != null) {
            try {
                remoteService.setOrientationConfig(landscapeStart);
                Log.d(TAG, "setOrientationConfig: landscapeStart=" + landscapeStart);
            } catch (Exception e) {
                Log.e(TAG, "setOrientationConfig: " + e.getMessage());
            }
        }
    }

    public void startGeteventListener() {
        if (remoteService != null) {
            try {
                // Use reflection to call the actual service instance, not the AIDL proxy
                java.lang.reflect.Method m = remoteService.getClass().getMethod("startGeteventListener");
                m.invoke(remoteService);
            } catch (Exception e) {
                Log.e(TAG, "startGeteventListener: " + e.getMessage());
            }
        }
    }

    public void stopGeteventListener() {
        if (remoteService != null) {
            try {
                java.lang.reflect.Method m = remoteService.getClass().getMethod("stopGeteventListener");
                m.invoke(remoteService);
            } catch (Exception e) {
                Log.e(TAG, "stopGeteventListener: " + e.getMessage());
            }
        }
    }

    public void blockPhysicalTouch() {
        if (remoteService != null) {
            try {
                remoteService.blockPhysicalTouch();
                Log.d(TAG, "blockPhysicalTouch called");
            } catch (Exception e) {
                Log.e(TAG, "blockPhysicalTouch: " + e.getMessage());
            }
        }
    }

    public void unblockPhysicalTouch() {
        if (remoteService != null) {
            try {
                remoteService.unblockPhysicalTouch();
                Log.d(TAG, "unblockPhysicalTouch called");
            } catch (Exception e) {
                Log.e(TAG, "unblockPhysicalTouch: " + e.getMessage());
            }
        }
    }

    public void destroyRemote() {
        if (remoteService != null) {
            try {
                java.lang.reflect.Method m = remoteService.getClass().getMethod("destroy");
                m.invoke(remoteService);
                Log.d(TAG, "destroyRemote called");
            } catch (Exception e) {
                Log.e(TAG, "destroyRemote: " + e.getMessage());
            }
        }
    }

    public int[] queryDeviceAbs(String devicePath, int axis) {
        // Query device ABS info via getevent
        try {
            java.lang.Process p = Runtime.getRuntime().exec("getevent -p " + devicePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // Format: 0035  : value 0, min 0, max 21199, fuzz 0, flat 0, resolution 0
                if (line.contains(String.format(" %04x ", axis))) {
                    String[] parts = line.split(",");
                    int min = 0, max = 0;
                    for (String part : parts) {
                        part = part.trim();
                        if (part.startsWith("min")) min = Integer.parseInt(part.split(" ")[1]);
                        if (part.startsWith("max")) max = Integer.parseInt(part.split(" ")[1]);
                    }
                    reader.close();
                    return new int[]{min, max};
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "queryDeviceAbs error: " + e.getMessage());
        }
        return new int[]{0, 0};
    }

    public String findTouchDevice() {
        try {
            java.lang.Process p = Runtime.getRuntime().exec("getevent -p");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // NOTE: 遗留过滤器，同 touch_core.cpp 的说明——虚拟设备现已不再
                // 使用品牌名命名，该条件恒为真。保留以维持行为不变。
                if (line.contains("ABS_X") && line.contains("ABS_Y") && !line.contains("YOLOVAIM")) {
                    // This is a real touchpanel, extract device path
                    int idx = line.indexOf("/dev/input");
                    if (idx >= 0) {
                        String path = line.substring(idx).split(" ")[0].trim();
                        reader.close();
                        return path;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "findTouchDevice error: " + e.getMessage());
        }
        return null;
    }

    public void disconnect() {
        connected = false;
        remoteService = null;
        try {
            Shizuku.unbindUserService(
                new Shizuku.UserServiceArgs(
                    new ComponentName(context, RemoteInjectorService.class))
                    .processNameSuffix("injector"),
                null, true
            );
        } catch (Exception e) {
            Log.w(TAG, "unbind error: " + e.getMessage());
        }
    }
}