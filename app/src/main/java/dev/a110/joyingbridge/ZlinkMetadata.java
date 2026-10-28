package dev.a110.joyingbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independently implemented from the supplied ZLink metadata client's iAP2 parser. */
final class ZlinkMetadata {
    static final long GUIDANCE = 8, MANEUVER = 9, MEDIA = 10;
    record Fields(Map<Integer, List<byte[]>> values) {
        byte[] get(int id) {
            List<byte[]> list = values.get(id);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
        }
        boolean has(int id) { return values.containsKey(id); }
        Long number(int id, int size) throws IOException {
            byte[] data = get(id); if (data == null) return null;
            if (data.length != size) throw new IOException("Field " + id + ": expected " + size + " bytes, got " + data.length);
            long value = 0; for (byte b : data) value = (value << 8) | (b & 255L); return value;
        }
        Integer integer(int id, int size) throws IOException { Long v = number(id, size); return v == null ? null : v.intValue(); }
        Integer signedAngle(int id) throws IOException {
            Integer value = integer(id, 2); return value == null ? null : (int) (short) value.intValue();
        }
        String text(int id) {
            byte[] data = get(id); if (data == null) return null;
            int length = 0; while (length < data.length && data[length] != 0) length++;
            return new String(data, 0, Math.min(length, 8192), StandardCharsets.UTF_8).trim();
        }
        int[] shorts(int id, boolean signed) throws IOException {
            List<byte[]> list = values.get(id); if (list == null) return null;
            List<Integer> result = new ArrayList<>();
            for (byte[] data : list) {
                if (data.length % 2 != 0) throw new IOException("Odd u16 array field " + id);
                for (int i = 0; i < data.length; i += 2) {
                    int v = ((data[i] & 255) << 8) | (data[i + 1] & 255);
                    result.add(signed ? (int) (short) v : v);
                }
            }
            return result.stream().mapToInt(Integer::intValue).toArray();
        }
    }
    static Fields parse(byte[] payload) throws IOException {
        Map<Integer, List<byte[]>> fields = new LinkedHashMap<>();
        int offset = 0;
        while (offset < payload.length) {
            if (payload.length - offset < 4) throw new IOException("Truncated iAP2 field header at " + offset);
            int size = ((payload[offset] & 255) << 8) | (payload[offset + 1] & 255);
            int id = ((payload[offset + 2] & 255) << 8) | (payload[offset + 3] & 255);
            if (size < 4 || size > payload.length - offset) throw new IOException("Invalid iAP2 field length " + size + " at " + offset);
            fields.computeIfAbsent(id, key -> new ArrayList<>()).add(Arrays.copyOfRange(payload, offset + 4, offset + size));
            offset += size;
        }
        return new Fields(fields);
    }
    static String name(long type) {
        if (type == GUIDANCE) return "CarPlay route guidance";
        if (type == MANEUVER) return "CarPlay maneuver";
        if (type == MEDIA) return "CarPlay media";
        return "Other ZLink metadata (" + type + ")";
    }
    private ZlinkMetadata() { }
}
