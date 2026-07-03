package team.maodie.aimbot.svc.aidl;

interface IShizukuServiceConnection {

    oneway void connected(IBinder service);

    oneway void died();
}
