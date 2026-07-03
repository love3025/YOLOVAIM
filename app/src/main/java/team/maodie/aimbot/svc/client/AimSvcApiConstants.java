package team.maodie.aimbot.svc.client;

import androidx.annotation.RestrictTo;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;

public class AimSvcApiConstants {

    public static final int SERVER_VERSION = 13;
    public static final int SERVER_PATCH_VERSION = 6;

    // binder
    public static final String BINDER_DESCRIPTOR = "team.maodie.aimbot.svc.aidl.IShizukuService";
    public static final int BINDER_TRANSACTION_transact = 1;

    // user service
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static final int USER_SERVICE_TRANSACTION_destroy = 16777115;

    public static final String USER_SERVICE_ARG_TAG = "aimbot:user-service-arg-tag";
    public static final String USER_SERVICE_ARG_COMPONENT = "aimbot:user-service-arg-component";
    public static final String USER_SERVICE_ARG_DEBUGGABLE = "aimbot:user-service-arg-debuggable";
    public static final String USER_SERVICE_ARG_VERSION_CODE = "aimbot:user-service-arg-version-code";
    public static final String USER_SERVICE_ARG_PROCESS_NAME = "aimbot:user-service-arg-process-name";
    public static final String USER_SERVICE_ARG_NO_CREATE = "aimbot:user-service-arg-no-create";
    public static final String USER_SERVICE_ARG_DAEMON = "aimbot:user-service-arg-daemon";
    public static final String USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS = "aimbot:user-service-arg-use-32-bit-app-process";
    public static final String USER_SERVICE_ARG_REMOVE = "aimbot:user-service-remove";

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static final String USER_SERVICE_ARG_TOKEN = "aimbot:user-service-arg-token";

    // bind application
    public static final String BIND_APPLICATION_SERVER_VERSION = "aimbot:attach-reply-version";
    public static final String BIND_APPLICATION_SERVER_PATCH_VERSION = "aimbot:attach-reply-patch-version";
    public static final String BIND_APPLICATION_SERVER_UID = "aimbot:attach-reply-uid";
    public static final String BIND_APPLICATION_SERVER_SECONTEXT = "aimbot:attach-reply-secontext";
    public static final String BIND_APPLICATION_PERMISSION_GRANTED = "aimbot:attach-reply-permission-granted";
    public static final String BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE = "aimbot:attach-reply-should-show-request-permission-rationale";

    // request permission
    public static final String REQUEST_PERMISSION_REPLY_ALLOWED = "aimbot:request-permission-reply-allowed";
    public static final String REQUEST_PERMISSION_REPLY_IS_ONETIME = "aimbot:request-permission-reply-is-onetime";

    // attach application
    public static final String ATTACH_APPLICATION_PACKAGE_NAME = "aimbot:attach-package-name";
    public static final String ATTACH_APPLICATION_API_VERSION = "aimbot:attach-api-version";
}
