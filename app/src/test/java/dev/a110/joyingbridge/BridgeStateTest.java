package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class BridgeStateTest {
    private long time;
    private final BridgeState s = new BridgeState(() -> time);
    static Bundle event(String id, String key, Object value) {
        Bundle b = new Bundle(); b.putString("id", id);
        if (value instanceof Integer) b.putInt(key, (Integer) value);
        else b.putString(key, (String) value);
        return b;
    }
    static Bundle nav(int type, int value) {
        Bundle b = event("main.cpaa.naviInfo", "type", type); b.putInt("value", value); return b;
    }
    private void liveDistance(int value) { s.accept(nav(11, 1)); s.accept(nav(4, value)); }
    private void music() {
        s.accept(event("canbus.carstr.update.16", "value", "Track"));
        s.accept(event("canbus.carstr.update.17", "value", "Artist"));
        s.accept(event("canbus.carstr.update.23", "value", "Album"));
    }
    @Test public void replaysObservedCaptureOrderingWithoutUsingPrivateTrackNames() throws Exception {
        s.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/joying-observed-sanitized.jsonl")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject e = new JSONObject(line), fields = e.getJSONObject("fields");
                time = e.getLong("elapsedMs");
                Bundle bundle = new Bundle();
                java.util.Iterator<String> keys = fields.keys();
                while (keys.hasNext()) {
                    String k = keys.next(); JSONObject field = fields.getJSONObject(k);
                    if ("Integer".equals(field.getString("type"))) bundle.putInt(k, field.getInt("value"));
                    else bundle.putString(k, field.isNull("value") ? null : field.getString("value"));
                }
                s.accept(bundle);
            }
        }
        assertEquals("Track6", s.media().title());
        assertEquals("Artist3", s.media().artist());
        assertEquals("Album3", s.media().album());
        assertTrue(s.media().playing());
        assertNull(s.navType());
    }
    @Test public void activeNavigationNeedsStateManeuverAndValidDistance() {
        s.start(); s.accept(nav(1, 2)); s.accept(nav(4, 250));
        assertNull(s.navType()); s.accept(nav(11, 1)); assertNull(s.navType()); s.accept(nav(4, 250));
        assertEquals("TL", s.navType()); assertEquals(250, s.navDistance());
        liveDistance(-1); assertNull(s.navType());
        liveDistance(0); assertEquals(0, s.navDistance()); assertEquals("TL", s.navType());
        s.accept(nav(11, 0)); assertNull(s.navType());
    }
    @Test public void replaysPodcastChaptersWithoutLosingUnchangedShowName() throws Exception {
        s.start();
        int preservedChapters = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/joying-podcast-sanitized.jsonl")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject e = new JSONObject(line);
                time = e.getLong("elapsedMs");
                if ("zlink_status".equals(e.getString("kind"))) { s.status(e.getString("message")); continue; }
                JSONObject fields = e.getJSONObject("fields");
                Bundle b = new Bundle();
                java.util.Iterator<String> keys = fields.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); JSONObject field = fields.getJSONObject(key);
                    if ("Integer".equals(field.getString("type"))) b.putInt(key, field.getInt("value"));
                    else b.putString(key, field.isNull("value") ? null : field.getString("value"));
                }
                s.accept(b);
                if ("canbus.carstr.update.16".equals(b.getString("id"))
                        && java.util.Arrays.asList("Podcast chapter2", "Podcast chapter3", "Podcast chapter4", "Podcast chapter6")
                            .contains(b.getString("value"))) {
                    assertEquals("Podcast name", s.media().artist());
                    assertEquals("Episode date", s.media().album());
                    preservedChapters++;
                }
            }
        }
        assertEquals(4, preservedChapters);
        assertEquals("Podcast chapter6", s.media().title());
        assertEquals("Podcast name", s.media().artist());
    }
    @Test public void staleGuidanceAndUnknownIconsNeverKeepAnOldArrow() {
        s.start(); s.accept(nav(1, 3)); liveDistance(100);
        assertEquals("TR", s.navType()); time = BridgeState.NAV_TIMEOUT_MS;
        assertNull(s.navType()); liveDistance(99); assertEquals("TR", s.navType());
        s.accept(nav(1, 60)); assertNull(s.navType());
        s.accept(nav(1, 255)); assertNull(s.navType());
    }
    @Test public void disconnectBlocksLateMetadataAndReconnectStartsEmpty() {
        s.start(); music(); s.status("DISCONNECT"); music();
        assertTrue(s.media().title().isEmpty());
        s.status("CONNECTED"); assertTrue(s.media().title().isEmpty());
        music(); assertEquals("Track", s.media().title());
        s.stop(); music(); assertTrue(s.media().title().isEmpty()); assertNull(s.navType());
    }
    @Test public void unknownPlaybackCompatibilityCanBeDisabledAndExplicitStateWins() {
        s.start(); music(); assertTrue(s.media().playing());
        s.inferPlaying(false); assertNull(s.media().playing());
        Bundle b = event("main.playerInfo", "infoType", 3); b.putInt("appId", 10); b.putInt("value", 2);
        s.accept(b); assertFalse(s.media().playing()); s.inferPlaying(true); assertFalse(s.media().playing());
        b.putInt("value", 0); s.accept(b); assertTrue(s.media().playing());
    }
    @Test public void nativeAudioSourceCannotReplaceCarPlayMetadata() {
        s.start(); music(); s.accept(event("main.appId", "value", 8)); music();
        assertTrue(s.media().title().isEmpty());
        s.accept(event("main.appId", "value", 10)); music(); assertEquals("Track", s.media().title());
        Bundle b = event("main.playerInfo", "infoType", 0); b.putInt("appId", 8); b.putString("value", "Wrong source");
        s.accept(b); assertEquals("Track", s.media().title());
    }
    @Test public void chapterTitleUpdateKeepsUnchangedPodcastContextButResetsTrackTiming() {
        s.start(); music(); s.accept(event("canbus.carstr.update.16", "value", "New"));
        assertEquals("New", s.media().title()); assertEquals("Artist", s.media().artist()); assertEquals("Album", s.media().album());
        Bundle timing = event("main.playerInfo", "infoType", 5);
        timing.putInt("appId", 10); timing.putInt("playTime", 10); timing.putInt("totalTime", 120);
        s.accept(timing); assertEquals(10_000, s.media().position());
        s.accept(event("canbus.carstr.update.16", "value", "Next chapter"));
        assertEquals(-1, s.media().position()); assertEquals(-1, s.media().duration());
        assertEquals("Artist", s.media().artist()); assertEquals("Album", s.media().album());
        s.accept(event("canbus.carstr.update.16", "value", null)); assertTrue(s.media().title().isEmpty());
        s.accept(event("canbus.carstr.update.16", "value", "New episode"));
        assertTrue(s.media().artist().isEmpty()); assertTrue(s.media().album().isEmpty());
    }
    @Test public void explicitlyClearedOrReplacedContextIsNotRestoredOnChapterChange() {
        s.start(); music();
        s.accept(event("canbus.carstr.update.17", "value", null));
        s.accept(event("canbus.carstr.update.23", "value", ""));
        s.accept(event("canbus.carstr.update.16", "value", "Next chapter"));
        assertTrue(s.media().artist().isEmpty()); assertTrue(s.media().album().isEmpty());
        s.accept(event("canbus.carstr.update.17", "value", "New podcast"));
        s.accept(event("canbus.carstr.update.23", "value", "New episode date"));
        s.accept(event("canbus.carstr.update.16", "value", "Another chapter"));
        assertEquals("New podcast", s.media().artist()); assertEquals("New episode date", s.media().album());
        s.accept(event("main.appId", "value", 8)); s.accept(event("main.appId", "value", 10));
        s.accept(event("canbus.carstr.update.16", "value", "Unidentified track"));
        assertTrue(s.media().artist().isEmpty()); assertTrue(s.media().album().isEmpty());
    }
    @Test public void playerInfoTitleUpdatesAlsoPreserveUnchangedContext() {
        s.start(); music();
        Bundle b = event("main.playerInfo", "infoType", 0);
        b.putInt("appId", 10); b.putString("value", "Next chapter"); s.accept(b);
        assertEquals("Artist", s.media().artist()); assertEquals("Album", s.media().album());
    }
    @Test public void serviceLossClearsDataAndNewEventsRecover() {
        s.start(); music(); s.lostSource(); assertTrue(s.media().title().isEmpty());
        music(); assertEquals("Track", s.media().title());
    }
}
