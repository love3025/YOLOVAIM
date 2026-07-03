package team.maodie.aimbot.svc.client;

import android.os.RemoteException;

/**
 * @since added from version 9
 */
public class AimSvcSystemProperties {

    public static String get(String key) throws RemoteException {
        return AimSvc.requireService().getSystemProperty(key, null);
    }

    public static String get(String key, String def) throws RemoteException {
        return AimSvc.requireService().getSystemProperty(key, def);
    }

    public static int getInt(String key, int def) throws RemoteException {
        return Integer.decode(AimSvc.requireService().getSystemProperty(key, Integer.toString(def)));
    }

    public static long getLong(String key, long def) throws RemoteException {
        return Long.decode(AimSvc.requireService().getSystemProperty(key, Long.toString(def)));
    }

    public static boolean getBoolean(String key, boolean def) throws RemoteException {
        return Boolean.parseBoolean(AimSvc.requireService().getSystemProperty(key, Boolean.toString(def)));
    }

    public static void set(String key, String val) throws RemoteException {
        AimSvc.requireService().setSystemProperty(key, val);
    }
}
