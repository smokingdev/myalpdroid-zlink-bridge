package dev.a110.joyingbridge;

import java.io.IOException;
import java.util.Arrays;
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
    private int[] selected = new int[0];
    private String packetDetails = "";
    private long distanceAt = -1;
    private boolean connected = true;
    private Long mediaId;
    private String title = "", artist = "", album = "";
    private Boolean playing;
    private long duration = -1, position = -1;
    ZlinkState(BridgeState target, LongSupplier clock) { this.target = target; this.clock = clock; }
    void reset() {
        maneuvers.clear(); component = activeIndex = distance = -1; state = 0; distanceAt = -1;
        selected = new int[0]; packetDetails = "";
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
        String details = packetDetails;
        if (type == ZlinkMetadata.GUIDANCE || type == ZlinkMetadata.MANEUVER) {
            details = "fields=" + fields.layout();
            if (type == ZlinkMetadata.GUIDANCE) {
                details += "; maneuver state=" + fields.integer(2, 1)
                        + "; destination=" + fields.text(4);
            } else {
                Integer rawType = fields.integer(3, 1);
                details += "; maneuver index=" + fields.integer(1, 2)
                        + "; raw type=" + rawType + " (" + eventName(rawType) + ")"
                        + "; description=" + fields.text(2);
            }
        }
        if (type == ZlinkMetadata.GUIDANCE) guidance(fields);
        else if (type == ZlinkMetadata.MANEUVER) maneuver(fields);
        else media(fields);
        packetDetails = details;
    }
    private void guidance(ZlinkMetadata.Fields f) throws IOException {
        Integer display = f.integer(0, 2), nextState = f.integer(1, 1);
        int[] current = f.shorts(13, false);
        Long nextDistance = f.number(10, 4);
        if (display == null) throw new IOException("Guidance missing display component");
        if (component != display) { selected = new int[0]; component = display; activeIndex = -1; distance = -1; distanceAt = -1; state = 0; }
        if (nextState != null) {
            if (state != nextState && nextState != 1) {
                maneuvers.clear(); selected = new int[0]; activeIndex = -1; distance = -1; distanceAt = -1;
            }
            state = nextState;
        }
        if (current != null && state == 1) {
            selected = current;
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
    private record Turn(String type, float angle, boolean left) { }
    private Turn decodeTurn(Maneuver m) {
        if (m == null || m.type == null) return new Turn(null, 0, false);
        int t = m.type;
        boolean roundabout = Integer.valueOf(1).equals(m.junction) || t == 6 || t == 7 || t == 19 || (t >= 28 && t <= 46);
        if (!roundabout) return new Turn(turn(t), 0, false);
        if (m.exitAngle == null || m.exitAngle < -180 || m.exitAngle > 180
                || m.side == null || (m.side != 0 && m.side != 1)) return new Turn(null, 0, false);
        int exit = t >= 28 && t <= 46 ? t - 27 : 0;
        return new Turn("RNDB" + (exit <= 9 ? exit : 0), m.exitAngle, m.side == 1);
    }
    private void publishNavigation() {
        Maneuver m = maneuvers.get(new Key(component, activeIndex));
        Turn primary = decodeTurn(m);
        // Only the explicit second selection is eligible; never infer index + 1 or
        // skip an unsupported maneuver to display a later turn out of sequence.
        Turn secondary = selected.length > 1 && selected[1] != activeIndex
                ? decodeTurn(maneuvers.get(new Key(component, selected[1]))) : new Turn(null, 0, false);
        // Historical MyAlpDroid applies the PRIMARY traffic-side flag to both arrows.
        // Suppress secondary symbols that cannot be represented faithfully.
        if (secondary.type != null && secondary.left != primary.left)
            secondary = new Turn(null, 0, false);
        target.directNavigation(primary.type, distance, m == null ? "" : m.road,
                primary.angle, primary.left, state == 1, distanceAt, secondary.type, secondary.angle);
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
    static String eventName(Integer type) {
        if (type == null) return "absent";
        return switch (type) {
            case 10 -> "arrival/end of navigation";
            case 11 -> "route start";
            case 12 -> "destination arrival";
            case 18 -> "route start with U-turn";
            case 24 -> "destination arrival left";
            case 25 -> "destination arrival right";
            case 27 -> "end of directions";
            default -> "maneuver";
        };
    }
    String describe() {
        return "component=" + component + "; state=" + state + "; active index=" + activeIndex
                + "; selected=" + Arrays.toString(Arrays.copyOf(selected, Math.min(selected.length, 32)))
                + "; selection count=" + selected.length + "; cached maneuvers=" + maneuvers.size()
                + "; " + packetDetails;
    }
}
