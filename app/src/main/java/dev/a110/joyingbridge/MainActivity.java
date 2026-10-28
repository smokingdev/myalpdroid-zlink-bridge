package dev.a110.joyingbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageButton toggle;
    private TextView status, hint;
    private DialView music, navigation;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        CaptureLog.initialize(this);
        LinearLayout page = Ui.page(this);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(getString(R.string.dashboard_title), 24, Ui.TEXT);
        titles.addView(title);
        status = label("", 14, Ui.MUTED); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = Ui.iconButton(this, false);
        settings.setImageDrawable(new ControlIcon(ControlIcon.SETTINGS, Ui.TEXT));
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        settingsSize.setMarginEnd(Ui.dp(this, 12)); header.addView(settings, settingsSize);
        toggle = Ui.iconButton(this, true);
        toggle.setOnClickListener(v -> toggleBridge());
        header.addView(toggle, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        page.addView(header);

        LinearLayout instruments = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        instruments.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        music = new DialView(this, false); navigation = new DialView(this, true);
        instruments.addView(music, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        instruments.addView(navigation, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        page.addView(instruments, new LinearLayout.LayoutParams(-1, 0, 1));
        hint = label("", 14, Ui.MUTED); hint.setGravity(Gravity.CENTER);
        page.addView(hint);
        setContentView(page);
        render();
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private void toggleBridge() {
        boolean running = CaptureLog.active();
        getSharedPreferences("bridge", 0).edit().putBoolean("enabled", !running).apply();
        if (running) stopService(new Intent(this, BridgeService.class));
        else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
            startForegroundService(new Intent(this, BridgeService.class));
        }
        render();
    }
    private void render() {
        boolean running = CaptureLog.active();
        BridgeState.Dashboard data = BridgeRuntime.state.dashboard();
        toggle.setImageDrawable(new ControlIcon(running ? ControlIcon.STOP : ControlIcon.PLAY, Ui.BACKGROUND));
        toggle.setContentDescription(getString(running ? R.string.stop_bridge : R.string.start_bridge));
        toggle.setTooltipText(toggle.getContentDescription());
        status.setText(!running ? R.string.stopped : Boolean.FALSE.equals(data.connected()) ? R.string.disconnected
                : data.media().title().isEmpty() && !data.routeActive() ? R.string.waiting : R.string.receiving);
        if (running) status.setText(getString(R.string.source_status,
                BridgeRuntime.directSource ? "ZLink direct" : "Joying", status.getText()));
        String error = getSharedPreferences("bridge", 0).getString("startupError", "none");
        hint.setText(!running && error != null && !error.equals("none") ? R.string.startup_failed
                : running ? R.string.running_hint : R.string.start_hint);
        music.update(data, running); navigation.update(data, running);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
