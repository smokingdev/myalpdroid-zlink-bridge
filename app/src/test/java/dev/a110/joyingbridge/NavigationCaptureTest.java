package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.os.Bundle;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NavigationCaptureTest {
    private long time;
    private final BridgeState state = new BridgeState(() -> time);
    private JSONArray capture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/joying-navigation-transitions-sanitized.json")) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray(name);
        }
    }
    private void apply(JSONObject row) throws Exception {
        time = row.getLong("elapsedMs");
        int type = row.getInt("type");
        Bundle b = new Bundle(); b.putString("id", "main.cpaa.naviInfo");
        b.putString("_ipc_caller", "com.car.media"); b.putInt("type", type);
        if (type == 3) b.putString("value", row.getString("value"));
        else b.putInt("value", row.getInt("value"));
        state.accept(b);
    }
    private void event(int type, int value) { state.accept(BridgeStateTest.nav(type, value)); }
    private void countdown(int distance) { event(11, 1); event(4, distance); }
    @Test public void recordedKeepLeftIgnoresSegmentLengthAndFollowsLiveCountdown() throws Exception {
        state.start(); event(1, 1); countdown(18);
        JSONArray rows = capture("keep-left");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); apply(row);
            if (row.getInt("type") == 4 && row.getInt("value") == 1100) assertNull(state.navType());
            if (row.getInt("type") == 4 && row.getInt("value") == 677) assertEquals(677, state.navDistance());
        }
        assertEquals("KL", state.navType()); assertEquals(654, state.navDistance());
    }
    @Test public void recordedZeroPlaceholderDoesNotBecomeFalseZeroMeterGuidance() throws Exception {
        state.start();
        JSONArray rows = capture("placeholder");
        for (int i = 0; i < rows.length(); i++) { apply(rows.getJSONObject(i)); assertNull(state.navType()); }
    }
    @Test public void recordedRoundaboutRemainsUnsupportedWithoutInventingAnExit() throws Exception {
        state.start();
        JSONArray rows = capture("roundabout");
        for (int i = 0; i < rows.length(); i++) { apply(rows.getJSONObject(i)); assertNull(state.navType()); }
    }
    @Test public void transitionStateClearsOldArrowAndNewLiveGuidanceRecovers() throws Exception {
        state.start(); event(1, 3); countdown(10);
        JSONArray rows = capture("transition");
        boolean sawTransition = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); apply(row);
            if (row.getInt("type") == 11 && row.getInt("value") == 5) {
                sawTransition = true; assertNull(state.navType());
            }
        }
        assertTrue(sawTransition); assertEquals("TSLR", state.navType()); assertEquals(500, state.navDistance());
    }
    @Test public void statusHeartbeatsAloneCannotKeepOldCountdownAlive() {
        state.start(); event(1, 3); countdown(100);
        for (int i = 1; i <= 30; i++) { time = i * 1000L; event(11, 1); }
        assertNull(state.navType());
        event(4, 90); assertEquals("TR", state.navType()); assertEquals(90, state.navDistance());
    }
    @Test public void unpairedOrDelayedDistanceCannotOverwriteLiveCountdown() {
        state.start(); event(1, 3); countdown(100);
        event(4, 5000); assertEquals(100, state.navDistance());
        event(11, 1); time = 1500; event(4, 9000); assertEquals(100, state.navDistance());
        event(1, 3); assertNull(state.navType());
        countdown(0); assertEquals("TR", state.navType()); assertEquals(0, state.navDistance());
    }
    @Test public void unconfirmedRouteStatesDoNotProduceAnArrowWithPlaceholderDistances() {
        for (int value : new int[]{3, 5, 6, 99}) {
            state.start(); event(1, 3); countdown(100);
            event(11, value); event(1, 1); event(4, 0);
            assertNull(state.navType());
            event(11, value); event(4, 8); assertNull(state.navType());
            event(1, 2); countdown(80);
            assertEquals("TL", state.navType()); assertEquals(80, state.navDistance());
        }
    }
}
