package dev.a110.joyingbridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class ZlinkPackets {
    static byte[] n(long value, int size) {
        byte[] b = new byte[size]; for (int i = size - 1; i >= 0; i--) { b[i] = (byte) value; value >>>= 8; } return b;
    }
    static byte[] field(int id, byte[] value) {
        return ByteBuffer.allocate(4 + value.length).putShort((short) (4 + value.length)).putShort((short) id).put(value).array();
    }
    static byte[] number(int id, long value, int size) { return field(id, n(value, size)); }
    static byte[] text(int id, String value) { return field(id, (value + "\0").getBytes(StandardCharsets.UTF_8)); }
    static byte[] join(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) out.write(field, 0, field.length); return out.toByteArray();
    }
    static byte[] frame(int type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(type).putInt(payload.length).put(payload).array();
    }
    static byte[] maneuver(int index, int type, Integer angle, int side) {
        return join(number(0, 42, 2), number(1, index, 2), number(3, type, 1), text(4, "Road " + index),
                number(5, 9000, 4), number(8, side, 1), angle == null ? new byte[0] : number(11, angle, 2));
    }
    static byte[] guidance(int index, long distance) {
        return join(number(0, 42, 2), number(1, 1, 1), number(10, distance, 4), number(13, index, 2));
    }
    private ZlinkPackets() { }
}
