package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import static dev.a110.joyingbridge.ZlinkPackets.*;
import java.io.IOException;
import org.junit.Test;

public class ZlinkMetadataTest {
    @Test public void knownWireBytesUseBigEndianFieldsInsideLittleEndianFrames() throws Exception {
        // TLV length 6, field 0, value 42. Negative angle -90 in field 11.
        byte[] raw = {0,6,0,0,0,42, 0,6,0,11,(byte)255,(byte)166};
        ZlinkMetadata.Fields f = ZlinkMetadata.parse(raw);
        assertEquals(Integer.valueOf(42), f.integer(0, 2)); assertEquals(Integer.valueOf(-90), f.signedAngle(11));
        assertNull(f.signedAngle(12));
    }
    @Test public void utf8AndRepeatedOrPackedJunctionAnglesArePreserved() throws Exception {
        ZlinkMetadata.Fields f = ZlinkMetadata.parse(join(text(4, "Road é"), number(10, -90, 2), field(10, join(n(0,2),n(90,2)))));
        assertEquals("Road é", f.text(4)); assertArrayEquals(new int[]{-90,0,90}, f.shorts(10, true));
    }
    @Test public void malformedLengthsAndTruncatedFieldsAreRejected() throws Exception {
        for (byte[] raw : new byte[][]{{0,0,0,0}, {0,3,0,0}, {0,8,0,0,1}, {0,4,0}}) {
            try { ZlinkMetadata.parse(raw); fail("Expected malformed TLV rejection"); } catch (IOException expected) { }
        }
    }
}
