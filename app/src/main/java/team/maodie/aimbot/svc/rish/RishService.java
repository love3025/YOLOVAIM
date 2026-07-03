package team.maodie.aimbot.svc.rish;

import android.os.Parcel;
import android.os.RemoteException;

/**
 * Stub for vendored Shizuku server. We do not support rish shell, so this class
 * is just a no-op: its onTransact always returns false, falling through to the
 * normal IShizukuService transact handler. The {@link #enforceCallingPermission(String)}
 * override is the only hook used by {@code Service}.
 */
public class RishService {

    public void enforceCallingPermission(String func) {
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return false;
    }
}