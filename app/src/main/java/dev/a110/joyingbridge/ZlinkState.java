package dev.a110.joyingbridge;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Selects the active route maneuver and merges independent metadata deltas. */
final class ZlinkState {
    private record Key(int component, int index) { }
    private static final class Maneuver {
        Integer type, side, junction, exitAngle;
        String road = "";
    }
    private final BridgeState target;
    private final LongSupplier clock;
    private final Map<Key, Maneuver> maneuvers = new HashMap<>();
    private int component = -1, state, distance = -1, activeIndex = -1;
    private long distanceAt = -1;
    private boolean connected = true;
    private Long mediaId;
    private String title = "", artist = "", album = "";
    private Boolean playing;
    private long duration = -1, position = -1;
    ZlinkState(BridgeState target, LongSupplier clock) { this.target = target; this.clock = clock; }
    void reset() {
        maneuvers.clear(); component = activeIndex = distance = -1; state = 0; distanceAt = -1;
        mediaId = null; title = artist = album = ""; playing = null; duration = position = -1;
    }
    void status(String value) {
        if ("DISCONNECT".equals(value)) { reset(); connected = false; }
        else if ("CONNECTED".equals(value)) { reset(); connected = true; }
    }
    void accept(long type, byte[] payload) throws IOException {
        if (!connected) return;
        if (type != ZlinkMetadata.GUIDANCE && type != ZlinkMetadata.MANEUVER && type != ZlinkMetadata.MEDIA) return;
        ZlinkMetadata.Fields fields = ZlinkMetadata.parse(payload);
        if (type == ZlinkMetadata.GUIDANCE) guidance(fields);
        else if (type == ZlinkMetadata.MANEUVER) maneuver(fields);
        else media(fields);
    }
    private void guidance(ZlinkMetadata.Fields f) throws IOException {
        Integer display = f.integer(0, 2), nextState = f.integer(1, 1);
        int[] current = f.shorts(13, false);
        Long nextDistance = f.number(10, 4);
        if (display == null) throw new IOException("Guidance missing display component");
        if (component != display) { component = display; activeIndex = -1; distance = -1; distanceAt = -1; state = 0; }
        if (nextState != null) {
            if (state != nextState && nextState != 1) {
                maneuvers.clear(); activeIndex = -1; distance = -1; distanceAt = -1;
            }
            state = nextState;
        }
        if (current != null) {
            int index = current.length == 0 ? -1 : current[0];
            if (activeIndex != index) { activeIndex = index; distance = -1; distanceAt = -1; }
        }
        if (nextDistance != null) {
            distance = nextDistance <= 1_000_000 ? nextDistance.intValue() : -1;
            distanceAt = distance >= 0 ? clock.getAsLong() : -1;
        }
        publishNavigation();
    }
    private void maneuver(ZlinkMetadata.Fields f) throws IOException {
        Integer display = f.integer(0, 2), index = f.integer(1, 2);
        if (display == null || index == null) throw new IOException("Maneuver missing component/index");
        Integer type = f.integer(3, 1), side = f.integer(8, 1), junction = f.integer(9, 1), angle = f.signedAngle(11);
        Key key = new Key(display, index);
        if (!maneuvers.containsKey(key) && maneuvers.size() >= 4096) throw new IOException("Maneuver cache full");
        Maneuver m = maneuvers.computeIfAbsent(key, ignored -> new Maneuver());
        if (f.has(3)) m.type = type;
        if (f.has(4)) m.road = f.text(4);
        if (f.has(8)) m.side = side;
        if (f.has(9)) m.junction = junction;
        if (f.has(11)) m.exitAngle = angle;
        publishNavigation();
    }
    private void publishNavigation() {
        Maneuver m = maneuvers.get(new Key(component, activeIndex));
        String type = null, road = ""; float angle = 0; boolean left = false;
        if (m != null) {
            road = m.road;
            if (m.type != null) {
                int t = m.type;
                boolean roundabout = Integer.valueOf(1).equals(m.junction) || t == 6 || t == 7 || t == 19 || (t >= 28 && t <= 46);
                if (roundabout) {
                    // Missing geometry remains inactive. A real signed zero is valid (straight ahead).
                    if (m.exitAngle != null && m.exitAngle >= -180 && m.exitAngle <= 180
                            && m.side != null && (m.side == 0 || m.side == 1)) {
                        int exit = t >= 28 && t <= 46 ? t - 27 : 0;
                        // Historical MyAlpDroid matches a single digit. Larger numbers do not render.
                        type = "RNDB" + (exit <= 9 ? exit : 0);
                        angle = m.exitAngle; left = m.side == 1;
                    }
                } else type = turn(t);
            }
        }
        target.directNavigation(type, distance, road, angle, left, state == 1, distanceAt);
    }
    private void media(ZlinkMetadata.Fields outer) throws IOException {
        // Validate the entire message before applying a partial item/playback update.
        ZlinkMetadata.Fields item = outer.has(0) ? ZlinkMetadata.parse(outer.get(0)) : null;
        ZlinkMetadata.Fields playback = outer.has(1) ? ZlinkMetadata.parse(outer.get(1)) : null;
        Long id = item == null ? null : item.number(0, 8);
        Long newDuration = item == null ? null : item.number(4, 4);
        Integer playbackStatus = playback == null ? null : playback.integer(0, 1);
        Long elapsed = playback == null ? null : playback.number(1, 4);
        if (item != null) {
            if (id != null && !id.equals(mediaId)) {
                mediaId = id; title = artist = album = ""; duration = position = -1;
            }
            if (item.has(1)) {
                String next = item.text(1);
                if (!next.equals(title)) { duration = position = -1; }
                title = next;
                if (title.isEmpty()) { artist = album = ""; duration = position = -1; }
            }
            if (item.has(12)) artist = item.text(12);
            else if (artist.isEmpty() && item.has(14)) artist = item.text(14);
            if (item.has(6)) album = item.text(6);
            if (newDuration != null) duration = newDuration;
        }
        if (playbackStatus != null) playing = playbackStatus == 1 ? true : playbackStatus == 0 || playbackStatus == 2 ? false : null;
        if (elapsed != null) position = elapsed;
        target.directMedia(new BridgeState.Media(title, artist, album, playing,
                playing == null ? "unknown" : "ZLink playback state", duration, position));
    }
    static String turn(int type) {
        return switch (type) {
            case 1, 52 -> "TL"; case 2, 53 -> "TR";
            case 3, 5, 8, 9, 11, 51 -> "C";
            case 4, 18, 26 -> "TU"; case 13 -> "KL"; case 14 -> "KR";
            case 20 -> "TSLL"; case 21 -> "TSLR"; case 22, 49 -> "TSLL"; case 23, 50 -> "TSLR";
            case 47 -> "TSHL"; case 48 -> "TSHR";
            default -> null;
        };
    }
    String describe() { return "component=" + component + "; state=" + state + "; active index=" + activeIndex + "; cached maneuvers=" + maneuvers.size(); }
}
