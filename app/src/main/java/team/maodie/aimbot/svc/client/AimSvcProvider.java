package team.maodie.aimbot.svc.client;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import team.maodie.aimbot.svc.api.BinderContainer;
import team.maodie.aimbot.svc.client.AimSvc;
import team.maodie.aimbot.svc.server.AimSvcServerConstants;

/**
 * <p>
 * Receives the AimSvc server binder via call("sendBinder", ...) from the AimSvc service
 * running under adb shell, then forwards it to {@link AimSvc}. This is the same flow as
 * upstream Shizuku, but with the provider authority renamed to .aimbot_svc.
 * </p>
 */
public class AimSvcProvider extends ContentProvider {

    private static final String TAG = "AimSvcProvider";

    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";

    public static final String ACTION_BINDER_RECEIVED = "team.maodie.aimbot.svc.api.action.BINDER_RECEIVED";

    public static final String EXTRA_BINDER = "team.maodie.aimbot.svc.intent.extra.BINDER";

    public static final String PERMISSION = AimSvcServerConstants.PERMISSION;
    public static final String MANAGER_APPLICATION_ID = AimSvcServerConstants.MANAGER_APPLICATION_ID;

    private static boolean enableMultiProcess = false;
    private static boolean isProviderProcess = false;

    public static void setIsProviderProcess(boolean isProviderProcess) {
        AimSvcProvider.isProviderProcess = isProviderProcess;
    }

    public static void enableMultiProcessSupport(boolean providerProcess) {
        Log.d(TAG, "Enable multi-process support (from " + (providerProcess ? "provider process" : "non-provider process") + ")");
        AimSvcProvider.isProviderProcess = providerProcess;
        AimSvcProvider.enableMultiProcess = true;
    }

    public static void requestBinderForNonProviderProcess(@NonNull Context context) {
        if (isProviderProcess) return;
        Log.d(TAG, "request binder in non-provider process");

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BinderContainer container = intent.getParcelableExtra(EXTRA_BINDER);
                if (container != null && container.binder != null) {
                    Log.i(TAG, "binder received from broadcast");
                    AimSvc.onBinderReceived(container.binder, context.getPackageName());
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED));
        }

        Bundle reply;
        try {
            reply = context.getContentResolver().call(
                    Uri.parse("content://" + context.getPackageName() + AimSvcServerConstants.PROVIDER_AUTHORITY_SUFFIX),
                    METHOD_GET_BINDER, null, new Bundle());
        } catch (Throwable tr) {
            reply = null;
        }

        if (reply != null) {
            reply.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = reply.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                Log.i(TAG, "Binder received from other process");
                AimSvc.onBinderReceived(container.binder, context.getPackageName());
            }
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) throw new IllegalStateException("android:multiprocess must be false");
        if (!info.exported) throw new IllegalStateException("android:exported must be true");
        isProviderProcess = true;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());

        Bundle reply = new Bundle();
        switch (method) {
            case METHOD_SEND_BINDER:
                handleSendBinder(extras);
                break;
            case METHOD_GET_BINDER:
                if (!handleGetBinder(reply)) return null;
                break;
        }
        return reply;
    }

    private void handleSendBinder(@NonNull Bundle extras) {
        if (AimSvc.pingBinder()) {
            Log.d(TAG, "sendBinder called when already a living binder");
            return;
        }
        BinderContainer container = extras.getParcelable(EXTRA_BINDER);
        if (container != null && container.binder != null) {
            Log.d(TAG, "binder received");
            AimSvc.onBinderReceived(container.binder, getContext().getPackageName());
            if (enableMultiProcess) {
                Intent intent = new Intent(ACTION_BINDER_RECEIVED)
                        .putExtra(EXTRA_BINDER, container)
                        .setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
            }
        }
    }

    private boolean handleGetBinder(@NonNull Bundle reply) {
        IBinder binder = AimSvc.getBinder();
        if (binder == null || !binder.pingBinder()) return false;
        reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
        return true;
    }

    @Nullable
    @Override public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Nullable
    @Override public final String getType(@NonNull Uri uri) { return null; }
    @Nullable
    @Override public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
