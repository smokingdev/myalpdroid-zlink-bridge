package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import static dev.a110.joyingbridge.ZlinkPackets.*;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ZlinkStateTest {
    private long time;
    private final BridgeState state = new BridgeState(() -> time);
    private final ZlinkState direct = new ZlinkState(state, () -> time);
    @Before public void start() { state.start(); state.source(true); }
    private byte[] selections(int... indices) {
        byte[][] entries = new byte[indices.length][];
        for (int i = 0; i < indices.length; i++) entries[i] = n(indices[i], 2);
        return join(number(0, 42, 2), number(1, 1, 1), number(10, 100, 4), field(13, join(entries)));
    }
    @Test public void secondTurnUsesExplicitNonConsecutiveIndexAndClearsWhenRemoved() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0));
        direct.accept(9, maneuver(2, 4, null, 0)); // Must not guess this index.
        direct.accept(8, selections(1, 9)); assertNull(state.secondNavType());
        direct.accept(9, maneuver(9, 2, null, 0));
        assertEquals("TL", state.navType()); assertEquals("TR", state.secondNavType());
        direct.accept(8, selections(1)); assertNull(state.secondNavType());
        direct.accept(8, selections(1, 9));
        time = BridgeState.NAV_TIMEOUT_MS; assertNull(state.secondNavType());
        direct.accept(8, selections(1, 9)); assertEquals("TR", state.secondNavType());
        state.source(false); assertNull(state.secondNavType());
    }
    @Test public void secondTurnDoesNotSkipUnsupportedOrDuplicateSelection() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0));
        direct.accept(9, maneuver(4, 12, null, 0));
        direct.accept(9, maneuver(9, 2, null, 0));
        direct.accept(8, selections(1, 4, 9)); assertNull(state.secondNavType());
        direct.accept(8, selections(1, 1)); assertNull(state.secondNavType());
        direct.accept(8, selections()); assertNull(state.secondNavType());
    }
    @Test public void secondaryRoundaboutNeedsGeometryAndCompatibleTrafficSide() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0));
        direct.accept(9, maneuver(9, 29, null, 0));
        direct.accept(8, selections(1, 9)); assertNull(state.secondNavType());
        direct.accept(9, maneuver(9, 29, -75, 0));
        assertEquals("RNDB2", state.secondNavType()); assertEquals(-75f, state.secondNavAngle(), 0);
        direct.accept(9, maneuver(9, 29, 75, 1)); assertNull(state.secondNavType());
        direct.accept(9, maneuver(1, 30, 90, 1));
        assertEquals("RNDB2", state.secondNavType()); assertEquals(75f, state.secondNavAngle(), 0);
        direct.accept(9, maneuver(9, 2, null, 1)); assertNull(state.secondNavType());
    }
    @Test public void secondarySelectionClearsOnRouteEndComponentChangeAndDisconnect() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(9, maneuver(9, 2, null, 0));
        direct.accept(8, selections(1, 9)); assertEquals("TR", state.secondNavType());
        direct.accept(8, join(number(0, 42, 2), number(1, 0, 1))); assertNull(state.secondNavType());
        direct.accept(8, selections(1, 9)); assertNull(state.secondNavType());
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(9, maneuver(9, 2, null, 0));
        assertEquals("TR", state.secondNavType());
        direct.accept(8, join(number(0, 99, 2), number(1, 1, 1), number(10, 100, 4)));
        assertNull(state.secondNavType());
        direct.status("DISCONNECT"); state.status("DISCONNECT"); assertNull(state.secondNavType());
    }
    @Test public void diagnosticsExposeSelectionEventsAndUnknownFieldLayout() throws Exception {
        direct.accept(8, join(selections(1, 9), number(2, 2, 1), text(4, "Destination"), field(42, new byte[3])));
        assertTrue(direct.describe().contains("selected=[1, 9]"));
        assertTrue(direct.describe().contains("destination=Destination"));
        assertTrue(direct.describe().contains("42:3"));
        direct.accept(9, join(maneuver(9, 24, null, 0), text(2, "Arrive on left")));
        assertTrue(direct.describe().contains("destination arrival left"));
        assertTrue(direct.describe().contains("description=Arrive on left"));
    }
    @Test public void routeBurstSelectsGuidanceIndexRatherThanLastManeuver() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(9, maneuver(2, 2, null, 0));
        direct.accept(8, guidance(1, 245));
        assertEquals("TL", state.navType()); assertEquals(245, state.navDistance());
        assertEquals("Road 1", state.dashboard().road());
        direct.accept(9, maneuver(3, 4, null, 0)); assertEquals("TL", state.navType());
        direct.accept(8, guidance(2, 120)); assertEquals("TR", state.navType());
        assertEquals(120, state.navDistance());
    }
    @Test public void missingSelectionAndMissingCacheNeverGuessWhichManeuverIsNext() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0));
        direct.accept(8, join(number(0, 42, 2), number(1, 1, 1), number(10, 100, 4)));
        assertNull(state.navType()); direct.accept(8, guidance(2, 99)); assertNull(state.navType());
        direct.accept(9, maneuver(2, 2, null, 0)); assertEquals("TR", state.navType()); assertEquals(99, state.navDistance());
    }
    @Test public void countdownComesFromGuidanceAndDoesNotUseSegmentLength() throws Exception {
        direct.accept(9, maneuver(1, 13, null, 0)); direct.accept(8, guidance(1, 677));
        assertEquals("KL", state.navType()); assertEquals(677, state.navDistance());
        direct.accept(9, maneuver(1, 13, null, 0)); assertEquals(677, state.navDistance());
    }
    @Test public void roundaboutForwardsSignedGeometryAndDrivingSide() throws Exception {
        direct.accept(9, maneuver(4, 29, -75, 0)); direct.accept(8, guidance(4, 250));
        assertEquals("RNDB2", state.navType()); assertEquals(-75f, state.navAngle(), 0); assertFalse(state.navLeft());
        direct.accept(9, maneuver(4, 30, 90, 1));
        assertEquals("RNDB3", state.navType()); assertEquals(90f, state.navAngle(), 0); assertTrue(state.navLeft());
    }
    @Test public void absentAngleDoesNotBecomeFalseZeroButAnExplicitZeroIsValid() throws Exception {
        direct.accept(9, maneuver(1, 29, null, 0)); direct.accept(8, guidance(1, 100)); assertNull(state.navType());
        direct.accept(9, join(number(0, 42, 2), number(1, 1, 2), number(11, 0, 2)));
        assertEquals("RNDB2", state.navType()); assertEquals(0f, state.navAngle(), 0);
        direct.accept(9, join(number(0, 42, 2), number(1, 1, 2), number(11, 360, 2))); assertNull(state.navType());
    }
    @Test public void unsupportedDrivingSideDoesNotChooseAnArbitraryRoundaboutDirection() throws Exception {
        direct.accept(9, maneuver(1, 29, 90, 2)); direct.accept(8, guidance(1, 100)); assertNull(state.navType());
    }
    @Test public void routeStateAndDisconnectionClearCachesBeforeReusingAnIndex() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(8, guidance(1, 100)); assertEquals("TL", state.navType());
        direct.accept(8, join(number(0, 42, 2), number(1, 0, 1))); assertNull(state.navType());
        direct.accept(8, guidance(1, 80)); assertNull(state.navType());
        direct.accept(9, maneuver(1, 2, null, 0)); assertEquals("TR", state.navType());
        direct.status("DISCONNECT"); state.status("DISCONNECT");
        direct.accept(8, guidance(1, 50)); assertNull(state.navType());
        direct.status("CONNECTED"); state.status("CONNECTED"); direct.accept(8, guidance(1, 50)); assertNull(state.navType());
    }
    @Test public void maneuverRefreshWithoutFreshCountdownCannotExtendGuidance() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(8, guidance(1, 100));
        time = BridgeState.NAV_TIMEOUT_MS;
        direct.accept(9, maneuver(1, 1, null, 0)); assertNull(state.navType());
        direct.accept(8, join(number(0, 42, 2), number(1, 1, 1))); assertNull(state.navType());
        direct.accept(8, guidance(1, 90)); assertEquals("TL", state.navType());
    }
    @Test public void sourceSwitchClearsOldMetadataAndRejectsTheOtherSourcesCallbacks() throws Exception {
        direct.accept(10, field(0, join(text(1, "Direct title"), text(12, "Direct artist"))));
        assertEquals("Direct title", state.media().title());
        state.accept(BridgeStateTest.event("canbus.carstr.update.16", "value", "Wrong source"));
        assertEquals("Direct title", state.media().title());
        state.source(false); assertEquals("", state.media().title());
        direct.accept(10, field(0, text(1, "Late direct title"))); assertEquals("", state.media().title());
        state.accept(BridgeStateTest.event("canbus.carstr.update.16", "value", "Joying title"));
        assertEquals("Joying title", state.media().title());
    }
    @Test public void mediaItemAndPlaybackDeltasPreservePodcastContextAndResetNewItemContext() throws Exception {
        direct.accept(10, field(0, join(number(0, 7, 8), text(1, "Chapter 1"), text(12, "Podcast"), text(6, "Episode"), number(4, 60000, 4))));
        direct.accept(10, field(1, join(number(0, 1, 1), number(1, 12000, 4))));
        assertTrue(state.media().playing()); assertEquals(12000, state.media().position()); assertEquals(60000, state.media().duration());
        direct.accept(10, field(0, text(1, "Chapter 2")));
        assertEquals("Chapter 2", state.media().title()); assertEquals("Podcast", state.media().artist());
        assertEquals("Episode", state.media().album()); assertEquals(-1, state.media().position());
        direct.accept(10, field(1, number(0, 2, 1))); assertFalse(state.media().playing());
        direct.accept(10, field(0, join(number(0, 8, 8), text(1, "New item"))));
        assertEquals("", state.media().artist()); assertEquals("", state.media().album());
    }
    @Test public void malformedPacketIsRejectedWithoutApplyingPartialMediaOrGuidance() throws Exception {
        direct.accept(9, maneuver(1, 1, null, 0)); direct.accept(8, guidance(1, 100));
        try { direct.accept(8, join(number(0, 42, 2), number(1, 0, 1), number(10, 10, 1))); fail(); }
        catch (IOException expected) { assertEquals("TL", state.navType()); assertEquals(100, state.navDistance()); }
        try { direct.accept(10, field(0, join(text(1, "Bad partial title"), number(4, 10, 1)))); fail(); }
        catch (IOException expected) { assertEquals("", state.media().title()); }
    }
}
