package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.car.ipc.ICallback;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class JoyingClientTest {
    private final RecordingBinder remote = new RecordingBinder();
    private final AtomicInteger callbacks = new AtomicInteger();
    private boolean cursorClosed;
    private IBinder offered;
    private JoyingClient client;
    private final ICallback.Stub callback = new ICallback.Stub() {
        @Override public void update(Bundle value) { if ("main.cpaa.naviInfo".equals(value.getString("id"))) callbacks.incrementAndGet(); }
    };

    private final class RecordingBinder extends Binder {
        int registrations, unregistrations;
        IBinder receivedCallback;
        RecordingBinder() { attachInterface(null, "com.car.ipc.IRemote"); }
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            // This fake implements the recovered wire layout directly, independently of the AIDL.
            assertTrue("Only read subscriptions permitted", code == 3 || code == 4);
            assertEquals(IBinder.FLAG_ONEWAY, flags);
            data.enforceInterface("com.car.ipc.IRemote");
            assertArrayEquals(JoyingClient.TOPICS, data.createStringArray());
            IBinder cb = data.readStrongBinder();
            assertSame(callback.asBinder(), cb);
            if (code == 3) {
                assertEquals(1, data.readInt());
                registrations++;
                receivedCallback = cb;
            } else unregistrations++;
            return true;
        }
    }

    @Before public void setup() {
        offered = remote;
        ShadowContentResolver.registerProviderInternal("com.car.service.provider", new ContentProvider() {
            @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
                assertEquals(JoyingClient.URI, uri.toString());
                return new MatrixCursor(new String[0]) {
                    @Override public Bundle getExtras() { Bundle b = new Bundle(); b.putBinder("IRemote", offered); return b; }
                    @Override public void close() { cursorClosed = true; super.close(); }
                };
            }
            @Override public boolean onCreate() { return true; }
            @Override public String getType(Uri uri) { return null; }
            @Override public Uri insert(Uri u, ContentValues v) { throw new AssertionError("Write attempted"); }
            @Override public int delete(Uri u, String s, String[] a) { throw new AssertionError("Write attempted"); }
            @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new AssertionError("Write attempted"); }
        });
        Context context = RuntimeEnvironment.getApplication();
        client = new JoyingClient(context.getContentResolver(), callback, () -> { });
    }
    @After public void cleanup() { client.close(); }
    @Test public void registersUsingRecoveredTransactionsAndClosesCursor() throws Exception {
        client.connect();
        assertTrue(cursorClosed);
        assertTrue(client.alive());
        assertEquals(1, remote.registrations);
        client.close();
        assertEquals(1, remote.unregistrations);
        assertFalse(client.alive());
        client.close();
        assertEquals(1, remote.unregistrations);
    }
    @Test public void reconnectUnregistersBeforeSubscribingAgain() throws Exception {
        client.connect(); client.connect();
        assertEquals(2, remote.registrations);
        assertEquals(1, remote.unregistrations);
    }
    @Test public void callbackAcceptsOriginalOneWayBundleFormat() throws Exception {
        client.connect();
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.car.ipc.ICallback");
            data.writeInt(1);
            Bundle value = new Bundle();
            value.putString("id", "main.cpaa.naviInfo"); value.putInt("type", 4); value.putInt("value", 250);
            value.writeToParcel(data, 0);
            assertTrue(remote.receivedCallback.transact(1, data, null, IBinder.FLAG_ONEWAY));
            assertEquals(1, callbacks.get());
        } finally { data.recycle(); }
    }
    @Test public void missingBinderFailsClearlyAndStillClosesCursor() {
        offered = null;
        assertThrows(IllegalStateException.class, () -> client.connect());
        assertTrue(cursorClosed);
        assertFalse(client.alive());
        assertEquals(0, remote.registrations);
    }
    @Test public void unexpectedDescriptorIsNotUsedAsTheVendorInterface() {
        Binder other = new Binder(); other.attachInterface(null, "unexpected.interface"); offered = other;
        assertThrows(IllegalStateException.class, () -> client.connect());
        assertFalse(client.alive());
        assertEquals(0, remote.registrations);
    }
}
