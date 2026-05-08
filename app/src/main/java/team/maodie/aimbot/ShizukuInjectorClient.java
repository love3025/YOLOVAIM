package team.maodie.aimbot;

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
import team.maodie.aimbot.IRemoteInjector;

public class ShizukuInjectorClient {
    private static final String TAG = "ShizukuInjector";
    private static final long CONNECT_TIMEOUT_MS = 10000;

    private final Context context;
    private volatile boolean connected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IRemoteInjector remoteService;

    public interface InjectorCallback {
        void onConnected();
        void onDisconnected();
        void onError(String msg);
    }

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
            Log.e(TAG, "Shizuku permission not granted: " + permResult);
            callback.onError("Shizuku permission not granted");
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
                remoteService.tap(x, y);
            } catch (Exception e) {
                Log.e(TAG, "tap error: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "tap called but remoteService is null");
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (remoteService != null) {
            try { remoteService.swipe(x1, y1, x2, y2, durationMs); } catch (Exception e) { Log.e(TAG, "swipe: " + e.getMessage()); }
        }
    }

    public void keepAlive() {
        if (remoteService != null) {
            try { remoteService.keepAlive(); } catch (Exception e) { Log.e(TAG, "keepAlive: " + e.getMessage()); }
        }
    }

    public boolean initRemote() {
        if (remoteService != null) {
            try {
                return remoteService.init();
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
                if (line.contains("ABS_X") && line.contains("ABS_Y") && !line.contains("Aimbot")) {
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