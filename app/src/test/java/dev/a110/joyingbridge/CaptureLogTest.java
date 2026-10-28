package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.os.Bundle;
import org.json.JSONObject;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class CaptureLogTest {
    @Before public void start() throws Exception { CaptureLog.begin(RuntimeEnvironment.getApplication()); }
    @Test public void preservesTopicKeyTypesAndSpecialTextAsValidJson() throws Exception {
        Bundle b = new Bundle();
        b.putString("id", "main.cpaa.naviInfo"); b.putInt("type", 4); b.putInt("value", 250);
        b.putString("road", "A \"road\"\nwith é");
        assertTrue(CaptureLog.callback(b));
        List<String> lines = Files.readAllLines(CaptureLog.file().toPath());
        JSONObject event = new JSONObject(lines.get(1));
        assertEquals("main.cpaa.naviInfo", event.getString("topic"));
        assertEquals(250, event.getJSONObject("fields").getJSONObject("value").getInt("value"));
        assertEquals("Integer", event.getJSONObject("fields").getJSONObject("value").getString("type"));
        assertEquals("A \"road\"\nwith é", event.getJSONObject("fields").getJSONObject("road").getString("value"));
    }
    @Test public void ignoresUnrequestedTopics() throws Exception {
        Bundle b = new Bundle(); b.putString("id", "unrequested.vehicle.data");
        CaptureLog.callback(b);
        assertEquals(1, Files.readAllLines(CaptureLog.file().toPath()).size());
    }
    @Test public void repeatedEventLimitTrimmingKeepsNewestEntriesInOrder() throws Exception {
        int total = CaptureLog.MAX_EVENTS * 3 + 37;
        for (int i = 0; i < total; i++) assertTrue(CaptureLog.record("test", "value " + i));
        List<String> lines = Files.readAllLines(CaptureLog.file().toPath());
        assertTrue(lines.size() <= CaptureLog.MAX_EVENTS);
        int first = total - lines.size();
        assertTrue(first > 0);
        for (int i = 0; i < lines.size(); i++) {
            assertEquals("value " + (first + i), new JSONObject(lines.get(i)).getString("message"));
        }
        assertTrue(CaptureLog.report().contains("Retained events: " + lines.size()));
        assertFalse(CaptureLog.report().contains("Older events trimmed: 0\n"));
        assertTrue(CaptureLog.active());
        assertTrue(CaptureLog.file().length() <= CaptureLog.MAX_BYTES);
    }
    @Test public void byteLimitTrimsWholeUtf8JsonLinesAndContinuesLogging() throws Exception {
        String payload = "é漢字\n\"".repeat(1500);
        int total = 300;
        for (int i = 0; i < total; i++) {
            assertTrue(CaptureLog.record("test", i + ":" + payload));
            assertTrue(CaptureLog.file().length() <= CaptureLog.MAX_BYTES);
        }
        List<String> lines = Files.readAllLines(CaptureLog.file().toPath());
        assertTrue(lines.size() < total);
        int first = total - lines.size();
        for (int i = 0; i < lines.size(); i++) {
            assertEquals((first + i) + ":" + payload, new JSONObject(lines.get(i)).getString("message"));
        }
    }
    @Test public void oversizedEntryDoesNotPreventLaterLogging() throws Exception {
        assertFalse(CaptureLog.record("test", "x".repeat((int) CaptureLog.MAX_BYTES)));
        assertTrue(CaptureLog.record("test", "after oversized entry"));
        List<String> lines = Files.readAllLines(CaptureLog.file().toPath());
        assertEquals(2, lines.size());
        assertEquals("after oversized entry", new JSONObject(lines.get(1)).getString("message"));
        assertTrue(CaptureLog.file().length() <= CaptureLog.MAX_BYTES);
    }
    @Test public void startingNewSessionReplacesOldCapture() throws Exception {
        CaptureLog.record("test", "old");
        CaptureLog.begin(RuntimeEnvironment.getApplication());
        assertEquals(1, Files.readAllLines(CaptureLog.file().toPath()).size());
    }
    @Test public void binaryPayloadIsNotWrittenAndLongStringsAreBounded() throws Exception {
        Bundle b = new Bundle(); b.putString("id", "main.playerInfo");
        b.putByteArray("art", new byte[]{1, 2, 3});
        b.putString("value", new String(new char[10000]).replace('\0', 'x'));
        CaptureLog.callback(b);
        JSONObject e = new JSONObject(Files.readAllLines(CaptureLog.file().toPath()).get(1));
        assertTrue(e.getJSONObject("fields").getJSONObject("art").isNull("value"));
        assertEquals(2048, e.getJSONObject("fields").getJSONObject("value").getString("value").length());
    }
}
