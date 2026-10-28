package dev.a110.joyingbridge;

import java.io.IOException;

/** ZLink metadata framing: unsigned little-endian type and payload length, then payload. */
final class ZlinkFrameDecoder {
    static final int MAX_PAYLOAD = 1024 * 1024;
    static final class FormatException extends IOException { FormatException(String message) { super(message); } }
    interface Listener { void frame(long type, byte[] payload) throws IOException; }
    private final Listener listener;
    private final byte[] header = new byte[8];
    private int headerUsed, payloadUsed;
    private long type;
    private byte[] payload;
    ZlinkFrameDecoder(Listener listener) { this.listener = listener; }
    void feed(byte[] bytes, int length) throws IOException {
        if (length < 0 || length > bytes.length) throw new IllegalArgumentException("Invalid chunk length");
        int position = 0;
        while (position < length) {
            if (payload == null) {
                int n = Math.min(8 - headerUsed, length - position);
                System.arraycopy(bytes, position, header, headerUsed, n); headerUsed += n; position += n;
                if (headerUsed < 8) continue;
                type = unsignedInt(header, 0); long size = unsignedInt(header, 4);
                if (size > MAX_PAYLOAD) throw new FormatException("Unrecognized framing: payload length " + size);
                payload = new byte[(int) size]; payloadUsed = 0;
            }
            int n = Math.min(payload.length - payloadUsed, length - position);
            System.arraycopy(bytes, position, payload, payloadUsed, n); payloadUsed += n; position += n;
            if (payloadUsed == payload.length) {
                byte[] complete = payload; long messageType = type;
                headerUsed = payloadUsed = 0; payload = null;
                listener.frame(messageType, complete);
            }
        }
    }
    String pending() { return payload == null ? headerUsed + "/8 header bytes" : payloadUsed + "/" + payload.length + " payload bytes"; }
    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 255) | (((long) bytes[offset + 1] & 255) << 8)
                | (((long) bytes[offset + 2] & 255) << 16) | (((long) bytes[offset + 3] & 255) << 24);
    }
}
