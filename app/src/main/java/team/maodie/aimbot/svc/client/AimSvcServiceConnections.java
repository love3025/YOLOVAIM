package team.maodie.aimbot.svc.client;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AimSvcServiceConnections {

    private static final Map<String, AimSvcServiceConnection> CACHE = Collections.synchronizedMap(new HashMap<>());

    @NonNull
    static AimSvcServiceConnection get(AimSvc.UserServiceArgs args) {
        String key = args.tag != null ? args.tag : args.componentName.getClassName();
        AimSvcServiceConnection connection = CACHE.get(key);

        if (connection == null) {
            connection = new AimSvcServiceConnection(args);
            CACHE.put(key, connection);
        }
        return connection;
    }

    static void remove(AimSvcServiceConnection connection) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, AimSvcServiceConnection> entry : CACHE.entrySet()) {
            if (entry.getValue() == connection) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CACHE.remove(key);
        }
    }
}
