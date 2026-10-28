package dev.a110.joyingbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            File file = CaptureLog.file();
            status.setText(BridgeRuntime.diagnostics() + "\n\n" + CaptureLog.report()
                    + "\nStartup error: " + getSharedPreferences("bridge", 0).getString("startupError", "none")
                    + "\n\nStored log: " + (file != null && file.exists() ? file.length() : 0) + " bytes");
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        CaptureLog.initialize(this);
        LinearLayout column = Ui.page(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(column);
        setContentView(scroll);
        android.widget.ImageButton back = Ui.iconButton(this, false);
        back.setContentDescription(getString(R.string.back));
        back.setImageDrawable(new ControlIcon(ControlIcon.BACK, Ui.TEXT));
        back.setOnClickListener(v -> finish());
        column.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        text(column, getString(R.string.settings), 28);
        text(column, getString(R.string.behavior), 14);
        android.widget.Switch direct = new android.widget.Switch(this);
        direct.setText(R.string.direct_zlink);
        Ui.styleSwitch(direct);
        direct.setChecked(getSharedPreferences("bridge", 0).getBoolean("directZlink", false));
        direct.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("bridge", 0).edit().putBoolean("directZlink", checked).apply();
            if (CaptureLog.active()) startService(new Intent(this, BridgeService.class));
        });
        column.addView(direct);
        text(column, getString(R.string.direct_zlink_help), 16);
        android.widget.Switch infer = new android.widget.Switch(this);
        infer.setText(R.string.infer_playing);
        Ui.styleSwitch(infer);
        infer.setChecked(getSharedPreferences("bridge", 0).getBoolean("inferPlaying", true));
        infer.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("bridge", 0).edit().putBoolean("inferPlaying", checked).apply();
            BridgeRuntime.state.inferPlaying(checked);
            if (CaptureLog.active()) startService(new Intent(this, BridgeService.class));
        });
        column.addView(infer);
        android.widget.Switch auto = new android.widget.Switch(this);
        auto.setText(R.string.auto_start);
        Ui.styleSwitch(auto);
        auto.setChecked(getSharedPreferences("bridge", 0).getBoolean("autoStart", true));
        auto.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("bridge", 0).edit().putBoolean("autoStart", checked).apply());
        column.addView(auto);
        text(column, getString(R.string.auto_start_help), 16);
        text(column, getString(R.string.setup), 14);
        text(column, getString(R.string.setup_help), 16);
        text(column, getString(R.string.diagnostics), 14);
        button(column, "Save diagnostics to Downloads", () -> {
            if (Build.VERSION.SDK_INT < 29) { message("Use Save capture file or ADB export on this Android version"); return; }
            new Thread(() -> {
                android.net.Uri uri = null;
                try {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "joying-bridge-" + System.currentTimeMillis() + ".jsonl");
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("Downloads provider unavailable");
                    synchronized (CaptureLog.class) {
                        try (FileInputStream in = new FileInputStream(CaptureLog.file());
                             OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new IllegalStateException("Cannot open Downloads file");
                            byte[] bytes = new byte[8192]; int n;
                            while ((n = in.read(bytes)) >= 0) out.write(bytes, 0, n);
                        }
                    }
                    values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    runOnUiThread(() -> message("Diagnostics saved to Downloads"));
                } catch (Exception e) {
                    if (uri != null) getContentResolver().delete(uri, null, null);
                    runOnUiThread(() -> message("Downloads export failed; use ADB export: " + e.getMessage()));
                }
            }, "downloads-export").start();
        });
        button(column, "Save diagnostics elsewhere…", () -> {
            File file = CaptureLog.file();
            if (file == null || !file.exists()) { message("No saved capture yet"); return; }
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "joying-capture-" + System.currentTimeMillis() + ".jsonl");
            try { startActivityForResult(create, 1); }
            catch (android.content.ActivityNotFoundException e) { message("No document picker. See README for ADB export."); }
        });
        text(column, getString(R.string.diagnostics_help), 16);
        button(column, getString(R.string.technical_details), () -> {
            status.setVisibility(status.getVisibility() == android.view.View.VISIBLE
                    ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        status = text(column, "", 14);
        status.setTextIsSelectable(true);
        status.setVisibility(android.view.View.GONE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri destination = data.getData();
        new Thread(() -> {
            try {
                synchronized (CaptureLog.class) {
                    try (FileInputStream in = new FileInputStream(CaptureLog.file());
                         OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                        if (out == null) throw new IllegalStateException("Cannot open destination");
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                }
                runOnUiThread(() -> message("Capture saved"));
            } catch (Exception e) { runOnUiThread(() -> message("Save failed: " + e.getMessage())); }
        }, "capture-export").start();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Ui.TEXT);
        view.setPadding(0, 12, 0, 12); parent.addView(view); return view;
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setTextColor(Ui.TEXT); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.PANEL));
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }
}
