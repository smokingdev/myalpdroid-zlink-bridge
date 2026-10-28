package dev.a110.joyingbridge;

import android.os.Bundle;
import java.util.function.LongSupplier;

/** Volatile state only. Methods are synchronized because Binder polls and vendor events overlap. */
final class BridgeState {
    static final long NAV_TIMEOUT_MS = 30_000;
    private static final long DISTANCE_PAIR_TIMEOUT_MS = 1_000;
    private final LongSupplier clock;
    private boolean enabled;
    private boolean directMode;
    private String directTurn;
    private float directAngle;
    private boolean directLeft;
    private Boolean connected;
    private Integer audioSource;
    private int icon, distance = -1;
    private boolean routeActive;
    private int routeState;
    private boolean liveDistancePending;
    private long liveDistanceRequestedAt;
    private long lastNav = -1;
    private String road = "", title = "", artist = "", album = "";
    private Boolean playing;
    private String playbackEvidence = "unknown";
    private boolean inferPlaying = true;
    private long durationMs = -1, positionMs = -1;

    BridgeState(LongSupplier clock) { this.clock = clock; }
    synchronized void start() { clear(); directMode = false; enabled = true; }
    synchronized void source(boolean direct) { clear(); directMode = direct; }
    synchronized void stop() { clear(); enabled = false; }
    synchronized void lostSource() { clear(); }
    private void clear() {
        connected = null; audioSource = null; clearNav(); clearMedia();
    }
    private void clearNav() {
        routeActive = false; routeState = 0; liveDistancePending = false;
        icon = 0; distance = -1; road = ""; lastNav = -1;
        directTurn = null; directAngle = 0; directLeft = false;
    }
    private void clearMedia() {
        title = artist = album = ""; playing = null; playbackEvidence = "unknown";
        durationMs = positionMs = -1;
    }
    synchronized void inferPlaying(boolean value) { inferPlaying = value; }
    synchronized void status(String value) {
        if (!enabled) return;
        if ("DISCONNECT".equals(value)) { clearNav(); clearMedia(); connected = false; }
        else if ("CONNECTED".equals(value)) { clearNav(); clearMedia(); connected = true; audioSource = null; }
        else if ("MAIN_AUDIO_START".equals(value) && !Boolean.FALSE.equals(connected)) {
            connected = true; playing = true; playbackEvidence = "ZLink audio-stream activity";
        } else if ("MAIN_AUDIO_STOP".equals(value)) {
            playing = false; playbackEvidence = "ZLink audio-stream activity";
        }
    }

    synchronized void accept(Bundle b) {
        if (!enabled || directMode || b == null) return;
        String id = b.getString("id", "");
        if ("main.appId".equals(id)) {
            audioSource = integer(b, "value");
            if (audioSource != null && audioSource != 10) clearMedia();
            return;
        }
        if (Boolean.FALSE.equals(connected)) return;
        if ("main.cpaa.naviInfo".equals(id)) {
            // Confirmed callbacks identify their producer; reject explicitly different sources.
            String caller = b.getString("_ipc_caller");
            if (caller != null && !"com.car.media".equals(caller)) return;
            Integer type = integer(b, "type"), value = integer(b, "value");
            if (type == null) return;
            if (type == 11 && value != null) {
                // State 1 accompanies the live countdown in the driving capture.
                // Other states include route setup/transition traffic, not confirmed guidance.
                if (value != 1) {
                    clearNav(); routeState = value; routeActive = value > 0 && value != 2;
                    return;
                }
                routeState = value;
                routeActive = true;
                liveDistancePending = true;
                liveDistanceRequestedAt = clock.getAsLong();
            } else if (type == 1 && value != null) {
                icon = value; distance = -1; lastNav = -1; road = "";
                liveDistancePending = false;
            }
            else if (type == 4 && value != null) {
                // Maneuver packets also carry type 4 (segment length or a zero placeholder).
                // Only the type-11/state-1 -> type-4 pair is the current distance countdown.
                if (!liveDistancePending) return;
                liveDistancePending = false;
                if (clock.getAsLong() - liveDistanceRequestedAt >= DISTANCE_PAIR_TIMEOUT_MS) return;
                distance = value >= 0 && value <= 1_000_000 ? value : -1;
                if (distance >= 0) lastNav = clock.getAsLong();
            } else if (type == 3) { road = string(b, "value"); liveDistancePending = false; }
            return;
        }
        if (audioSource != null && audioSource != 10) return;
        if ("main.playerInfo".equals(id)) {
            Integer app = integer(b, "appId"), type = integer(b, "infoType");
            if (app == null || app != 10 || type == null) return;
            if (type >= 0 && type <= 2) mediaField(type, string(b, "value"));
            else if (type == 3) {
                Integer v = integer(b, "value");
                playing = v != null && v == 0 ? true : v != null && v == 2 ? false : null;
                playbackEvidence = playing == null ? "unknown" : "Joying player state";
            } else if (type == 5) {
                Integer time = integer(b, "playTime"), duration = integer(b, "totalTime");
                positionMs = time != null && time >= 0 ? time.longValue() * 1000 : -1;
                durationMs = duration != null && duration >= 0 ? duration.longValue() * 1000 : -1;
            }
        } else if ("canbus.carstr.update.16".equals(id)) mediaField(0, string(b, "value"));
        else if ("canbus.carstr.update.17".equals(id)) mediaField(1, string(b, "value"));
        else if ("canbus.carstr.update.23".equals(id)) mediaField(2, string(b, "value"));
    }
    private void mediaField(int type, String value) {
        if (type == 0) {
            // Joying sends changed fields, not full snapshots. Podcast chapter changes
            // update only the title; the artist/album still describe the same episode.
            // Explicit empty fields and connection/source resets clear that context.
            if (!title.equals(value)) { durationMs = positionMs = -1; }
            title = value;
            if (title.isEmpty()) clearMedia();
        } else if (type == 1) artist = value;
        else album = value;
    }
    synchronized String navType() {
        if (!enabled || !routeActive || lastNav < 0 || clock.getAsLong() - lastNav >= NAV_TIMEOUT_MS
                || distance < 0 || Boolean.FALSE.equals(connected)) return null;
        return directMode ? directTurn : mapIcon(icon);
    }
    synchronized void directNavigation(String type, int meters, String nextRoad, float angle,
                                       boolean left, boolean active, long updatedAt) {
        if (!enabled || !directMode || Boolean.FALSE.equals(connected)) return;
        directTurn = type; distance = meters; road = nextRoad; directAngle = angle;
        directLeft = left; routeActive = active; lastNav = updatedAt;
    }
    synchronized void directMedia(Media value) {
        if (!enabled || !directMode || Boolean.FALSE.equals(connected)) return;
        title = value.title(); artist = value.artist(); album = value.album(); playing = value.playing();
        playbackEvidence = value.evidence(); durationMs = value.duration(); positionMs = value.position();
    }
    synchronized float navAngle() { return navType() == null ? 0 : directAngle; }
    synchronized boolean navLeft() { return navType() != null && directLeft; }
    synchronized int navDistance() { return navType() == null ? 0 : distance; }
    synchronized Media media() {
        if (!enabled || title.isEmpty()) return new Media("", "", "", null, "inactive", -1, -1);
        Boolean state = playing != null ? playing : inferPlaying ? Boolean.TRUE : null;
        return new Media(title, artist, album, state, playing != null ? playbackEvidence
                : inferPlaying ? "inferred for MyAlpDroid compatibility" : "unknown", durationMs, positionMs);
    }
    synchronized String describe() {
        return "ZLink connection: " + connected + "; Joying audio source: " + audioSource
                + "\nNavigation: " + (navType() == null ? "inactive / incomplete / unsupported" : navType() + " " + distance + " m")
                + "; route=" + routeActive + "; route state=" + routeState + "; Joying icon=" + icon + "\nRoad: " + road
                + "\nDirect ZLink: " + directMode + "; exit angle=" + directAngle + "; left traffic=" + directLeft
                + "\nTrack: " + title + "\nArtist: " + artist + "\nAlbum: " + album
                + "\nPlayback: " + media().playing + " (" + media().evidence + ")";
    }
    record Media(String title, String artist, String album, Boolean playing, String evidence, long duration, long position) { }
    synchronized Dashboard dashboard() {
        return new Dashboard(media(), navType(), navDistance(), road, routeActive, icon, connected, navAngle());
    }
    record Dashboard(Media media, String turn, int distance, String road, boolean routeActive,
                     int icon, Boolean connected, float angle) { }
    static String mapIcon(int icon) {
        return switch (icon) {
            case 1, 89 -> "C";
            case 2 -> "TL";
            case 3 -> "TR";
            case 4 -> "TU";
            case 9 -> "TRU";
            case 7, 12 -> "TSLL";
            case 5, 13 -> "TSLR";
            case 8 -> "TSHL";
            case 6 -> "TSHR";
            case 10 -> "KL";
            case 11 -> "KR";
            default -> null; // Roundabouts lack the actual exit angle in this vendor feed.
        };
    }
    private static Integer integer(Bundle b, String key) { Object v = b.get(key); return v instanceof Integer ? (Integer) v : null; }
    private static String string(Bundle b, String key) { Object v = b.get(key); return v instanceof String ? ((String) v).trim() : ""; }
}
