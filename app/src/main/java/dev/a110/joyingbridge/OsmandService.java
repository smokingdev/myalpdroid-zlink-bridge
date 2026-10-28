package dev.a110.joyingbridge;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import net.osmand.aidlapi.IOsmAndAidlInterface;
import net.osmand.aidlapi.info.AppInfoParams;

public final class OsmandService extends Service {
    private final IOsmAndAidlInterface.Stub binder = new IOsmAndAidlInterface.Stub() {
        @Override public AppInfoParams getAppInfo() {
            BridgeRuntime.polled(Binder.getCallingUid());
            return BridgeRuntime.appInfo();
        }
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION && code != 79)
                CaptureLog.record("ipc", "Unsupported OsmAnd transaction " + code);
            return super.onTransact(code, data, reply, flags);
        }
    };
    @Override public IBinder onBind(Intent intent) {
        return "net.osmand.aidl.OsmandAidlServiceV2".equals(intent.getAction()) ? binder : null;
    }
}
