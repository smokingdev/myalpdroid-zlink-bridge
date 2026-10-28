package dev.a110.joyingbridge;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Bounded JSONL capture. Raw callback keys/types are retained to discover actual semantics. */
final class CaptureLog {
    static final int MAX_EVENTS = 5000;
    static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final ArrayDeque<String> tail = new ArrayDeque<>();
    private static final ArrayDeque<Integer> entrySizes = new ArrayDeque<>();
    private static final Map<String, Integer> counts = new HashMap<>();
    private static int events;
    private static long bytes;
    private static long trimmed;
    private static String status = "Stopped";
    private static boolean active;
    private static File file;

    static synchronized void initialize(Context context) {
        file = new File(context.getFilesDir(), "joying-capture.jsonl");
    }

    static synchronized void begin(Context context) throws Exception {
        initialize(context);
        new AtomicFile(file).delete();
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.flush(); }
        tail.clear(); entrySizes.clear(); counts.clear(); events = 0; bytes = 0; trimmed = 0;
        active = true;
        status = "Starting";
        record("status", "Started bridge diagnostics");
    }

    static synchronized boolean record(String kind, String message) {
        try {
            JSONObject event = base(kind);
            event.put("message", message);
            return append(event);
        } catch (Exception e) { status = "Log error: " + e; return false; }
    }

    static synchronized void zlinkRaw(long connection, long offset, byte[] data, int length) {
        try {
            JSONObject event = base("zlink_tcp"); event.put("source", "zlink");
            event.put("connection", connection); event.put("streamOffset", offset); event.put("length", length);
            event.put("base64", android.util.Base64.encodeToString(data, 0, length, android.util.Base64.NO_WRAP));
            append(event);
        } catch (Exception e) { status = "Log error: " + e; }
    }
    static synchronized void zlinkFrame(long connection, long type, byte[] data) {
        try {
            JSONObject event = base("zlink_frame"); event.put("source", "zlink");
            event.put("connection", connection); event.put("messageType", type); event.put("name", ZlinkMetadata.name(type));
            event.put("payloadLength", data.length);
            event.put("payloadBase64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
            append(event);
        } catch (Exception e) { status = "Log error: " + e; }
    }

    static synchronized boolean callback(Bundle bundle) {
        try {
            String id = bundle.getString("id");
            if (!Arrays.asList(JoyingClient.TOPICS).contains(id)) return true;
            JSONObject event = base("callback");
            event.put("topic", id);
            JSONObject values = new JSONObject();
            // No vendor parcelable classes are loaded; only scalar values are written to JSON.
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                JSONObject field = new JSONObject();
                field.put("type", value == null ? "null" : value.getClass().getSimpleName());
                if (value instanceof String) field.put("value", truncate((String) value, 2048));
                else if (value instanceof Number || value instanceof Boolean) field.put("value", value);
                else field.put("value", JSONObject.NULL);
                values.put(truncate(key, 128), field);
            }
            event.put("fields", values);
            boolean saved = append(event);
            if (saved) counts.put(id, counts.getOrDefault(id, 0) + 1);
            return saved;
        } catch (Exception e) { return record("decode_error", e.toString()); }
    }

    private static JSONObject base(String kind) throws Exception {
        JSONObject object = new JSONObject();
        object.put("kind", kind);
        object.put("wallTimeMs", System.currentTimeMillis());
        object.put("elapsedMs", SystemClock.elapsedRealtime());
        return object;
    }

    private static boolean append(JSONObject event) throws Exception {
        byte[] data = (event + "\n").getBytes(StandardCharsets.UTF_8);
        if (file == null || data.length > MAX_BYTES) {
            status = file == null ? "Log not initialized" : "Log entry too large; skipped";
            return false;
        }
        if (events >= MAX_EVENTS || bytes + data.length > MAX_BYTES) trimAndAppend(data);
        else try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(data); }
        bytes += data.length; events++;
        entrySizes.addLast(data.length);
        if (tail.size() >= 12) tail.removeFirst();
        tail.addLast(event.toString());
        return true;
    }

    /** Reclaim a batch to avoid rewriting a full log on every callback once it fills up. */
    private static void trimAndAppend(byte[] data) throws Exception {
        int remove = 0;
        long removeBytes = 0;
        for (int size : entrySizes) {
            if (events - remove + 1 <= MAX_EVENTS * 4 / 5
                    && bytes - removeBytes + data.length <= MAX_BYTES * 4 / 5) break;
            remove++;
            removeBytes += size;
        }
        AtomicFile replacement = new AtomicFile(file);
        FileOutputStream out = null;
        // Open the old file before AtomicFile starts replacing it (also works on older Android).
        try (FileInputStream in = new FileInputStream(file)) {
            out = replacement.startWrite();
            byte[] buffer = new byte[8192];
            long remaining = removeBytes;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new java.io.IOException("Unexpected end of log");
                remaining -= n;
            }
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.write(data);
            replacement.finishWrite(out);
        } catch (Exception error) {
            if (out != null) replacement.failWrite(out);
            throw error;
        }
        for (int i = 0; i < remove; i++) entrySizes.removeFirst();
        events -= remove; bytes -= removeBytes; trimmed += remove;
    }

    static synchronized void status(String value) { status = value; record("status", value); }
    static synchronized void stopped() { active = false; }
    static synchronized boolean active() { return active; }
    static synchronized String report() {
        return "State: " + status + "\nBridge running: " + active + "\nRetained events: " + events
                + "\nOlder events trimmed: " + trimmed
                + "\nSession callbacks by topic: " + counts + "\n\nRecent events:\n" + String.join("\n\n", tail);
    }
    static synchronized File file() { return file; }
    private static String truncate(String value, int size) {
        return value.length() <= size ? value : value.substring(0, size);
    }
}
