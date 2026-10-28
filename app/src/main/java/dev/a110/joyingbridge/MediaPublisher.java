package dev.a110.joyingbridge;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/** Metadata only: no player commands, audio focus request, audio output or media-button routing. */
final class MediaPublisher implements AutoCloseable {
    interface Sink {
        void metadata(MediaMetadata metadata);
        void playback(PlaybackState state);
        void active(boolean active);
        void release();
    }
    private final Sink sink;
    private boolean closed;
    MediaPublisher(Context context) {
        MediaSession session = new MediaSession(context, "ZLink → MyAlpDroid");
        sink = new Sink() {
            public void metadata(MediaMetadata value) { session.setMetadata(value); }
            public void playback(PlaybackState value) { session.setPlaybackState(value); }
            public void active(boolean value) { session.setActive(value); }
            public void release() { session.release(); }
        };
    }
    MediaPublisher(Sink sink) { this.sink = sink; }
    void publish(BridgeState.Media media) {
        if (closed) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, media.title())
                // Historical MyAlpDroid rejects title-only tracks. Use a source label, not
                // an invented artist, when the feed has no current artist context.
                .putString(MediaMetadata.METADATA_KEY_ARTIST, media.artist().isEmpty() && !media.title().isEmpty()
                        ? "CarPlay" : media.artist())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, media.album());
        if (media.duration() >= 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, media.duration());
        // Historical MyAlpDroid needs metadata before its playback-state event.
        sink.metadata(metadata.build());
        int playback = media.title().isEmpty() ? PlaybackState.STATE_STOPPED
                : media.playing() == null ? PlaybackState.STATE_NONE
                : media.playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        sink.playback(new PlaybackState.Builder().setActions(0)
                .setState(playback, media.position() >= 0 ? media.position() : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playback == PlaybackState.STATE_PLAYING ? 1f : 0f).build());
        sink.active(!media.title().isEmpty());
    }
    @Override public void close() { sink.active(false); sink.release(); closed = true; }
}
