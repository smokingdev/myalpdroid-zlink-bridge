package dev.a110.joyingbridge;

import android.os.Bundle;
import android.os.SystemClock;
import net.osmand.aidlapi.info.AppInfoParams;

final class BridgeRuntime {
    static final BridgeState state = new BridgeState(SystemClock::elapsedRealtime);
    static volatile boolean directSource;
    static volatile String sourceStatus = "Stopped";
    static volatile long directBytes, directFrames;
    static volatile String directDetails = "";
    private static long polls;
    private static int uid = -1;
    static synchronized void polled(int caller) { polls++; uid = caller; }
    static synchronized String diagnostics() {
        return "Source: " + (directSource ? "ZLink direct" : "Joying") + "; " + sourceStatus
                + "\nDirect bytes=" + directBytes + "; frames=" + directFrames + "; " + directDetails
                + "\nMyAlpDroid getAppInfo polls: " + polls + "; UID: " + uid + "\n" + state.describe();
    }
    static AppInfoParams appInfo() {
        synchronized (state) {
            String type = state.navType();
            Bundle turn = new Bundle();
            turn.putString("next_turn_type", type == null ? "no type" : type);
            turn.putInt("next_turn_distance", state.navDistance());
            turn.putFloat("next_turn_angle", state.navAngle());
            turn.putBoolean("next_turn_possibly_left", state.navLeft());
            String second = state.secondNavType();
            turn.putString("no_speak_next_turn_type", second == null ? "no type" : second);
            turn.putInt("no_speak_next_turn_distance", 0);
            turn.putFloat("no_speak_next_turn_angle", state.secondNavAngle());
            return new AppInfoParams(null, null, turn, 0, 0, type == null ? 0L : 1L, false);
        }
    }
}
