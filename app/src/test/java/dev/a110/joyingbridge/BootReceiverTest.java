package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BootReceiverTest {
    private final Application app = RuntimeEnvironment.getApplication();
    @Before public void reset() { app.getSharedPreferences("bridge", 0).edit().clear().commit(); }
    @Test public void freshInstallDoesNotAutoEnableTheBridge() {
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(Shadows.shadowOf(app).getNextStartedService());
    }
    @Test public void enabledBridgeRestartsAfterBootAndPackageUpdate() {
        app.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", true).commit();
        for (String action : new String[]{Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
            new BootReceiver().onReceive(app, new Intent(action));
            Intent started = Shadows.shadowOf(app).getNextStartedService();
            assertNotNull(started); assertEquals(BridgeService.class.getName(), started.getComponent().getClassName());
        }
    }
    @Test public void autoStartToggleAndManualStopAreRespected() {
        app.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", true).putBoolean("autoStart", false).commit();
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(Shadows.shadowOf(app).getNextStartedService());
        app.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", false).putBoolean("autoStart", true).commit();
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(Shadows.shadowOf(app).getNextStartedService());
    }
    @Test public void unrelatedBroadcastCannotStartBridge() {
        app.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", true).commit();
        new BootReceiver().onReceive(app, new Intent("other.action"));
        assertNull(Shadows.shadowOf(app).getNextStartedService());
    }
}
