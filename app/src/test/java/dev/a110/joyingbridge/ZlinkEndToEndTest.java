package dev.a110.joyingbridge;

import static org.junit.Assert.*;
import static dev.a110.joyingbridge.ZlinkPackets.*;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ZlinkEndToEndTest {
    @After public void reset() { BridgeRuntime.state.stop(); }
    @Test public void tcpMetadataReachesOsmAndRoundaboutOutputAndMediaSessionWithoutSendingCommands() throws Exception {
        BridgeRuntime.state.start(); BridgeRuntime.state.source(true);
        ZlinkState state = new ZlinkState(BridgeRuntime.state, () -> android.os.SystemClock.elapsedRealtime());
        byte[] wire = join(frame(9, maneuver(1, 1, null, 0)), frame(9, maneuver(2, 29, -75, 0)),
                frame(10, join(field(0, join(text(1, "Chapter"), text(12, "Podcast"))), field(1, number(0, 1, 1)))),
                frame(8, guidance(2, 250)));
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ended = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            Future<?> sent = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(wire, 0, 3); socket.getOutputStream().flush();
                    socket.getOutputStream().write(wire, 3, wire.length - 3); socket.getOutputStream().flush();
                    socket.setSoTimeout(200);
                    try { int value = socket.getInputStream().read(); fail("Unexpected client byte/close: " + value); }
                    catch (SocketTimeoutException expected) { /* No registration or commands sent. */ }
                } catch (Exception e) { throw new AssertionError(e); }
            });
            try (ZlinkClient client = new ZlinkClient("127.0.0.1", server.getLocalPort(), new ZlinkClient.Listener() {
                public void status(String message) { }
                public void error(String message) { }
                public void raw(byte[] bytes, int length) { }
                public void ended() { ended.countDown(); }
                public void frame(long type, byte[] payload) {
                    try { state.accept(type, payload); } catch (Throwable e) { error.set(e); }
                }
            })) {
                assertTrue(ended.await(5, TimeUnit.SECONDS)); sent.get(3, TimeUnit.SECONDS);
                if (error.get() != null) throw new AssertionError(error.get());
                var info = BridgeRuntime.appInfo();
                assertTrue(info.getArrivalTime() > 0);
                assertEquals("RNDB2", info.getTurnInfo().getString("next_turn_type"));
                assertEquals(-75f, info.getTurnInfo().getFloat("next_turn_angle"), 0);
                assertEquals(250, info.getTurnInfo().getInt("next_turn_distance"));
                assertFalse(info.getTurnInfo().getBoolean("next_turn_possibly_left"));
                class Sink implements MediaPublisher.Sink {
                    MediaMetadata metadata; PlaybackState playback;
                    public void metadata(MediaMetadata m) { metadata = m; }
                    public void playback(PlaybackState p) { playback = p; }
                    public void active(boolean value) { }
                    public void release() { }
                }
                Sink sink = new Sink();
                try (MediaPublisher publisher = new MediaPublisher(sink)) { publisher.publish(BridgeRuntime.state.media()); }
                assertEquals("Podcast", sink.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                assertEquals("Chapter", sink.metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                assertEquals(PlaybackState.STATE_PLAYING, sink.playback.getState());
            }
        } finally { worker.shutdownNow(); }
    }
}
