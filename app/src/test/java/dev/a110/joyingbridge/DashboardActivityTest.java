package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DashboardActivityTest {
    private final Application app = RuntimeEnvironment.getApplication();
    @Before public void reset() {
        app.getSharedPreferences("bridge", 0).edit().clear().commit();
        CaptureLog.stopped(); BridgeRuntime.state.stop();
    }
    @Test public void startControlAndSettingsOpenTheCorrectComponents() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            find(root, activity.getString(R.string.start_bridge)).performClick();
            Intent service = Shadows.shadowOf(app).getNextStartedService();
            assertEquals(BridgeService.class.getName(), service.getComponent().getClassName());
            assertTrue(app.getSharedPreferences("bridge", 0).getBoolean("enabled", false));
            find(root, activity.getString(R.string.settings)).performClick();
            assertEquals(SettingsActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        }
    }
    @Test public void runningBridgeOffersStopAndPersistsItsDisabledState() throws Exception {
        CaptureLog.begin(app); BridgeRuntime.state.start();
        app.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", true).commit();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            find(activity.getWindow().getDecorView(), activity.getString(R.string.stop_bridge)).performClick();
            assertFalse(app.getSharedPreferences("bridge", 0).getBoolean("enabled", true));
        }
    }
    @Test public void settingsPersistWithoutStartingAStoppedBridge() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Switch auto = findSwitch(controller.get().getWindow().getDecorView(), R.string.auto_start);
            assertTrue(auto.isChecked()); auto.setChecked(false);
            assertFalse(app.getSharedPreferences("bridge", 0).getBoolean("autoStart", true));
            assertNull(Shadows.shadowOf(app).getNextStartedService());
        }
    }
    @Test public void directSourceDefaultsOffAndCanBeChangedWithoutStartingStoppedBridge() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Switch direct = findSwitch(controller.get().getWindow().getDecorView(), R.string.direct_zlink);
            assertFalse(direct.isChecked()); direct.setChecked(true);
            assertTrue(app.getSharedPreferences("bridge", 0).getBoolean("directZlink", false));
            assertNull(Shadows.shadowOf(app).getNextStartedService());
        }
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Switch direct = findSwitch(controller.get().getWindow().getDecorView(), R.string.direct_zlink);
            assertTrue(direct.isChecked()); direct.setChecked(false);
            assertFalse(app.getSharedPreferences("bridge", 0).getBoolean("directZlink", true));
        }
    }
    @Test public void sourceChangeWhileRunningNotifiesExistingServiceWithoutClearingCapture() throws Exception {
        CaptureLog.begin(app);
        CaptureLog.record("test", "before source change");
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Switch direct = findSwitch(controller.get().getWindow().getDecorView(), R.string.direct_zlink);
            direct.setChecked(true);
            assertEquals(BridgeService.class.getName(), Shadows.shadowOf(app).getNextStartedService().getComponent().getClassName());
            assertTrue(new String(java.nio.file.Files.readAllBytes(CaptureLog.file().toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("before source change"));
        }
    }
    @Test @Config(qualifiers = "w1000dp-h600dp-land")
    public void landscapeDashboardDrawsLiveDataAndClearsStoppedValues() throws Exception { checkRendering(); }
    @Test @Config(qualifiers = "w400dp-h800dp-port")
    public void portraitDashboardDrawsLiveDataAndClearsStoppedValues() throws Exception { checkRendering(); }
    private void checkRendering() throws Exception {
        CaptureLog.begin(app);
        BridgeRuntime.state.start();
        field("canbus.carstr.update.16", "A long title that should wrap instead of overflowing the instrument face");
        field("canbus.carstr.update.17", "Test artist");
        nav(1, 3); field("main.cpaa.naviInfo", "A very long road name", 3); nav(11, 1); nav(4, 450);
        BridgeState.Dashboard data = BridgeRuntime.state.dashboard();
        for (boolean navigation : new boolean[]{false, true}) {
            DialView dial = new DialView(app, navigation); dial.update(data, true);
            dial.layout(0, 0, 500, 500);
            dial.draw(new Canvas(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)));
            String description = dial.getContentDescription().toString();
            assertTrue(navigation ? description.contains("450 m") && description.contains("Turn right")
                    : description.contains("Test artist") && description.contains("A long title"));
            dial.update(data, false);
            assertFalse(dial.getContentDescription().toString().contains(navigation ? "450 m" : "Test artist"));
        }
        // Use short, clearly fictional metadata for the README screenshot; long text is exercised above.
        field("canbus.carstr.update.16", "Night Drive");
        field("canbus.carstr.update.17", "Demo artist");
        field("canbus.carstr.update.23", "Evening playlist");
        field("main.cpaa.naviInfo", "Riverside Road", 3);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View view = controller.get().getWindow().getDecorView();
            int width = app.getResources().getConfiguration().screenWidthDp;
            int height = app.getResources().getConfiguration().screenHeightDp;
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image));
            java.io.File output = new java.io.File("build/dashboard-" + width + "x" + height + ".png");
            output.getParentFile().mkdirs();
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
                image.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }
    private void field(String id, String value) { field(id, value, -1); }
    private void field(String id, String value, int type) {
        Bundle b = new Bundle(); b.putString("id", id); b.putString("value", value);
        b.putInt("type", type); BridgeRuntime.state.accept(b);
    }
    private void nav(int type, int value) {
        Bundle b = new Bundle(); b.putString("id", "main.cpaa.naviInfo"); b.putInt("type", type);
        b.putInt("value", value); BridgeRuntime.state.accept(b);
    }
    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static Switch findSwitch(View view, int text) {
        if (view instanceof Switch s && s.getText().toString().equals(view.getContext().getString(text))) return s;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Switch found = findSwitch(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
}
