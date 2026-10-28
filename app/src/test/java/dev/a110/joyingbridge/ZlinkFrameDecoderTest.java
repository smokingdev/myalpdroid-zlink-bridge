package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ZlinkFrameDecoderTest {
    static byte[] packet(long type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) type).putInt(payload.length).put(payload).array();
    }
    @Test public void fragmentedTcpReadsPreserveTypeAndEveryPayloadByte() throws Exception {
        List<Long> types = new ArrayList<>(); List<byte[]> payloads = new ArrayList<>();
        ZlinkFrameDecoder d = new ZlinkFrameDecoder((type, payload) -> { types.add(type); payloads.add(payload); });
        byte[] payload = new byte[]{0, 1, -1, 10, 40, 0}; byte[] raw = packet(0xF0000011L, payload);
        for (byte b : raw) d.feed(new byte[]{b}, 1);
        assertEquals(Arrays.asList(0xF0000011L), types); assertArrayEquals(payload, payloads.get(0));
        assertEquals("0/8 header bytes", d.pending());
    }
    @Test public void coalescedMessagesAndEmptyPayloadsAreDecodedSeparately() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(packet(1, new byte[0])); bytes.write(packet(2, new byte[]{3, 4})); bytes.write(packet(3, new byte[0]));
        List<Integer> lengths = new ArrayList<>(); List<Long> types = new ArrayList<>();
        ZlinkFrameDecoder d = new ZlinkFrameDecoder((type, payload) -> { types.add(type); lengths.add(payload.length); });
        byte[] all = bytes.toByteArray(); d.feed(all, all.length);
        assertEquals(Arrays.asList(1L, 2L, 3L), types); assertEquals(Arrays.asList(0, 2, 0), lengths);
    }
    @Test public void incompletePayloadIsRetainedUntilNextRead() throws Exception {
        List<byte[]> received = new ArrayList<>(); ZlinkFrameDecoder d = new ZlinkFrameDecoder((type, payload) -> received.add(payload));
        byte[] raw = packet(5, new byte[]{7, 8, 9});
        d.feed(Arrays.copyOf(raw, 9), 9); assertTrue(received.isEmpty()); assertEquals("1/3 payload bytes", d.pending());
        d.feed(Arrays.copyOfRange(raw, 9, 11), 2); assertArrayEquals(new byte[]{7, 8, 9}, received.get(0));
    }
    @Test public void invalidUnsignedLengthIsRejectedBeforeAllocation() throws Exception {
        ZlinkFrameDecoder d = new ZlinkFrameDecoder((type, payload) -> fail("Cannot emit an invalid frame"));
        byte[] header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-1).array();
        try { d.feed(header, 8); fail("Expected length rejection"); }
        catch (ZlinkFrameDecoder.FormatException expected) { assertTrue(expected.getMessage().contains("4294967295")); }
    }
}
