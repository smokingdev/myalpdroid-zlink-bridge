package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import android.content.Intent;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.os.Parcel;
import net.osmand.aidlapi.IOsmAndAidlInterface;
import net.osmand.aidlapi.info.AppInfoParams;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class OutputContractTest {
    @After public void reset() { BridgeRuntime.state.stop(); }
    @Test public void joyingTurnReachesHistoricalOsmAndWireContractAndClearsOnStop() throws Exception {
        BridgeRuntime.state.start();
        BridgeRuntime.state.accept(BridgeStateTest.nav(1, 2));
        BridgeRuntime.state.accept(BridgeStateTest.nav(11, 1));
        BridgeRuntime.state.accept(BridgeStateTest.nav(4, 250));
        OsmandService service = Robolectric.buildService(OsmandService.class).create().get();
        IOsmAndAidlInterface.Stub binder = (IOsmAndAidlInterface.Stub) service.onBind(new Intent("net.osmand.aidl.OsmandAidlServiceV2"));
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("net.osmand.aidlapi.IOsmAndAidlInterface"); data.setDataPosition(0);
            assertTrue(binder.onTransact(79, data, reply, 0)); reply.setDataPosition(0); reply.readException();
            assertEquals(1, reply.readInt()); AppInfoParams info = AppInfoParams.CREATOR.createFromParcel(reply);
            assertTrue(info.getArrivalTime() > 0); assertEquals("TL", info.getTurnInfo().getString("next_turn_type"));
            assertEquals(250, info.getTurnInfo().getInt("next_turn_distance"));
        } finally { data.recycle(); reply.recycle(); }
        BridgeRuntime.state.stop(); assertEquals(0L, binder.getAppInfo().getArrivalTime());
    }
    @Test public void androidMediaSessionContainsTrackThenPlaybackState() {
        BridgeState state = new BridgeState(() -> 0); state.start();
        state.accept(BridgeStateTest.event("canbus.carstr.update.16", "value", "Title"));
        state.accept(BridgeStateTest.event("canbus.carstr.update.17", "value", "Artist"));
        class Output implements MediaPublisher.Sink {
            MediaMetadata metadata; PlaybackState playback; boolean active;
            java.util.List<String> calls = new java.util.ArrayList<>();
            public void metadata(MediaMetadata value) { metadata = value; calls.add("metadata"); }
            public void playback(PlaybackState value) { playback = value; calls.add("playback"); }
            public void active(boolean value) { active = value; calls.add("active"); }
            public void release() { calls.add("release"); }
        }
        Output output = new Output();
        MediaPublisher publisher = new MediaPublisher(output);
        try {
            publisher.publish(state.media());
            assertTrue(output.active);
            assertEquals(java.util.Arrays.asList("metadata", "playback", "active"), output.calls);
            assertEquals("Title", output.metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            assertEquals("Artist", output.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            assertEquals(PlaybackState.STATE_PLAYING, output.playback.getState());
            assertEquals(0L, output.playback.getActions());
            state.accept(BridgeStateTest.event("canbus.carstr.update.16", "value", "Next chapter"));
            publisher.publish(state.media());
            assertEquals("Next chapter", output.metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            assertEquals("Artist", output.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            state.accept(BridgeStateTest.event("canbus.carstr.update.17", "value", ""));
            publisher.publish(state.media());
            assertEquals("CarPlay", output.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            state.stop(); publisher.publish(state.media()); assertFalse(output.active);
        } finally { publisher.close(); }
    }
}
