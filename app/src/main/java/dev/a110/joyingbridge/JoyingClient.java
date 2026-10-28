package dev.a110.joyingbridge;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import com.car.ipc.ICallback;
import com.car.ipc.IRemote;

/** Recovered wire contract, independently implemented. Only register/unregister are used. */
final class JoyingClient implements AutoCloseable {
    static final String URI = "content://com.car.service.provider";
    static final String[] TOPICS = {"main.cpaa.naviInfo", "main.playerInfo", "main.appId",
            "canbus.carstr.update.16", "canbus.carstr.update.17", "canbus.carstr.update.23"};
    private final ContentResolver resolver;
    private final ICallback callback;
    private final IBinder.DeathRecipient death;
    private IBinder binder;
    private IRemote remote;

    JoyingClient(ContentResolver resolver, ICallback callback, IBinder.DeathRecipient death) {
        this.resolver = resolver;
        this.callback = callback;
        this.death = death;
    }

    void connect() throws Exception {
        close();
        try (Cursor cursor = resolver.query(Uri.parse(URI), null, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Provider returned no cursor");
            Bundle extras = cursor.getExtras();
            binder = extras == null ? null : extras.getBinder("IRemote");
        }
        if (binder == null) throw new IllegalStateException("Provider did not return IRemote Binder");
        if (!"com.car.ipc.IRemote".equals(binder.getInterfaceDescriptor())) {
            binder = null;
            throw new IllegalStateException("Unexpected Binder interface descriptor");
        }
        try {
            binder.linkToDeath(death, 0);
            remote = IRemote.Stub.asInterface(binder);
            remote.register(TOPICS.clone(), callback, true);
        } catch (Exception e) { close(); throw e; }
    }

    boolean alive() { return binder != null && binder.isBinderAlive(); }

    @Override public void close() {
        if (remote != null && binder != null && binder.isBinderAlive()) {
            try { remote.unregister(TOPICS.clone(), callback); } catch (Exception ignored) { }
        }
        if (binder != null) {
            try { binder.unlinkToDeath(death, 0); } catch (Exception ignored) { }
        }
        remote = null;
        binder = null;
    }
}
