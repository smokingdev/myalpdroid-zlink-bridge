package dev.a110.joyingbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.Locale;
import java.util.Objects;

/** A scalable instrument face. Ring progress is shown only when a real track duration is known. */
final class DialView extends View {
    private final boolean navigation;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private BridgeState.Dashboard data;
    private boolean running;
    DialView(Context context, boolean navigation) {
        super(context); this.navigation = navigation;
        setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void update(BridgeState.Dashboard value, boolean enabled) {
        if (Objects.equals(data, value) && running == enabled) return;
        data = value; running = enabled;
        setContentDescription(description()); invalidate();
    }
    private String s(int id) { return getContext().getString(id); }
    private boolean hasMusic() { return running && data != null && !data.media().title().isEmpty(); }
    private boolean hasNavigation() { return running && data != null && data.turn() != null; }
    private boolean roundabout() {
        return data != null && data.routeActive() && ((data.icon() >= 57 && data.icon() <= 84)
                || data.turn() != null && data.turn().startsWith("RNDB"));
    }
    private String navLabel() {
        if (!running || data == null || !data.routeActive()) return s(R.string.no_guidance);
        if (roundabout()) {
            if (data.turn() != null && data.turn().matches("RNDB[1-9]"))
                return getContext().getString(R.string.roundabout_exit, Integer.parseInt(data.turn().substring(4)));
            return s(R.string.roundabout);
        }
        if (data.turn() == null) return s(R.string.incomplete_guidance);
        return s(switch (data.turn()) {
            case "TL" -> R.string.left; case "TR" -> R.string.right;
            case "TSLL" -> R.string.slight_left; case "TSLR" -> R.string.slight_right;
            case "TSHL" -> R.string.sharp_left; case "TSHR" -> R.string.sharp_right;
            case "KL" -> R.string.keep_left; case "KR" -> R.string.keep_right;
            case "TU", "TRU" -> R.string.u_turn;
            default -> R.string.straight;
        });
    }
    private String playbackLabel() {
        BridgeState.Media m = data.media();
        if (Boolean.FALSE.equals(m.playing())) return s(R.string.paused);
        // An inferred state should not be presented as confirmed playback on the dashboard.
        if (Boolean.TRUE.equals(m.playing()) && !m.evidence().startsWith("inferred")) return s(R.string.playing);
        return s(R.string.track_received);
    }
    private String description() {
        if (navigation) return s(R.string.navigation) + ": " + navLabel()
                + (hasNavigation() ? ", " + distance(data.distance()) : "")
                + (running && data != null && !data.road().isEmpty() ? ", " + data.road() : "");
        return s(R.string.music) + ": " + (hasMusic() ? data.media().title() + ", "
                + data.media().artist() + ", " + data.media().album() + ", " + playbackLabel() : s(R.string.no_track));
    }
    static String distance(int meters) {
        return meters < 1000 ? meters + " m" : String.format(Locale.getDefault(), "%.1f km", meters / 1000f);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float scale = Math.min(getWidth() / 420f, getHeight() / 420f);
        c.save(); c.translate((getWidth() - 420 * scale) / 2, (getHeight() - 420 * scale) / 2);
        c.scale(scale, scale);
        int accent = navigation ? Ui.NAV : Ui.MUSIC;
        boolean live = navigation ? hasNavigation() : hasMusic();
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF101923); c.drawCircle(210, 210, 187, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xFF263645);
        c.drawCircle(210, 210, 187, paint);
        for (int i = 0; i <= 40; i++) {
            c.save(); c.rotate(-135 + i * 6.75f, 210, 210);
            paint.setColor(i % 5 == 0 ? 0xFF60758A : 0xFF2C4053);
            paint.setStrokeWidth(i % 5 == 0 ? 2.5f : 1.5f);
            c.drawLine(210, 34, 210, i % 5 == 0 ? 47 : 41, paint); c.restore();
        }
        RectF arc = new RectF(54, 54, 366, 366);
        paint.setStrokeWidth(4); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(0xFF213342);
        c.drawArc(arc, 135, 270, false, paint);
        if (live) {
            paint.setColor(accent);
            if (!navigation && data.media().duration() > 0 && data.media().position() >= 0) {
                float progress = Math.min(1f, data.media().position() / (float) data.media().duration());
                c.drawArc(arc, 135, 270 * progress, false, paint);
            } else {
                // A small marker denotes incoming data; it is not a fabricated distance/progress gauge.
                c.drawArc(arc, 267, 6, false, paint);
            }
        }
        line(c, s(navigation ? R.string.navigation : R.string.music), 99, 16, accent, true);
        if (navigation) drawNavigation(c, accent); else drawMusic(c, accent);
        paint.setStyle(Paint.Style.FILL); paint.setColor(live ? accent : Ui.MUTED);
        c.drawCircle(173, 358, 3, paint);
        line(c, live ? "LIVE" : "WAITING", 363, 11, Ui.MUTED, true, 219);
        c.restore();
    }
    private void drawMusic(Canvas c, int accent) {
        musicIcon(c, hasMusic() ? accent : Ui.MUTED);
        if (!hasMusic()) {
            block(c, s(R.string.no_track), 211, 23, Ui.TEXT, true, 2);
            block(c, running ? s(R.string.waiting_music) : s(R.string.stopped), 275, 16, Ui.MUTED, false, 2);
            return;
        }
        BridgeState.Media m = data.media();
        block(c, m.title(), 202, 24, Ui.TEXT, true, 2);
        block(c, m.artist().isEmpty() ? "CarPlay" : m.artist(), 263, 18, Ui.MUTED, false, 1);
        block(c, m.album(), 290, 14, Ui.MUTED, false, 1);
        line(c, playbackLabel(), 329, 13, accent, false);
    }
    private void drawNavigation(Canvas c, int accent) {
        arrow(c, hasNavigation() ? data.turn() : null, hasNavigation() ? accent : Ui.MUTED);
        if (hasNavigation()) {
            line(c, distance(data.distance()), 234, 43, Ui.TEXT, true);
            block(c, navLabel(), 250, 19, Ui.TEXT, true, 1);
            block(c, data.road().isEmpty() ? s(R.string.next_maneuver) : data.road(), 285, 16, Ui.MUTED, false, 2);
        } else {
            block(c, running ? navLabel() : s(R.string.no_guidance), 210, 22, Ui.TEXT, true, 2);
            block(c, !running ? s(R.string.stopped) : roundabout() ? s(R.string.roundabout_unavailable)
                    : data != null && data.routeActive() ? s(R.string.unsupported_turn) : s(R.string.waiting_nav),
                    275, 16, Ui.MUTED, false, 2);
        }
    }
    private void musicIcon(Canvas c, int color) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
        Path p = new Path(); p.moveTo(198, 165); p.lineTo(198, 129); p.lineTo(228, 122); p.lineTo(228, 158);
        c.drawPath(p, paint); paint.setStyle(Paint.Style.FILL);
        c.drawOval(184, 158, 198, 169, paint); c.drawOval(214, 151, 228, 162, paint);
    }
    private void arrow(Canvas c, String turn, int color) {
        if (turn == null) {
            paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(3);
            c.drawCircle(210, 153, 22, paint);
            line(c, "—", 160, 22, color, false); return;
        }
        c.save(); c.translate(210, 154);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(7);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        Path p = new Path();
        if (turn.startsWith("RNDB")) {
            c.drawCircle(0, 0, 20, paint);
            c.drawLine(0, 20, 0, 32, paint);
            c.rotate(data.angle());
            p.moveTo(0, -20); p.lineTo(0, -38);
            p.moveTo(-9, -29); p.lineTo(0, -38); p.lineTo(9, -29);
        } else if (turn.equals("C")) {
            p.moveTo(0, 28); p.lineTo(0, -28); p.moveTo(-15, -12); p.lineTo(0, -28); p.lineTo(15, -12);
        } else if (turn.equals("TU") || turn.equals("TRU")) {
            if (turn.equals("TRU")) c.scale(-1, 1);
            p.moveTo(17, 28); p.lineTo(17, -8); p.cubicTo(17, -35, -17, -35, -17, -8);
            p.lineTo(-17, 16); p.moveTo(-30, 3); p.lineTo(-17, 16); p.lineTo(-4, 3);
        } else {
            boolean left = turn.contains("L");
            if (left) c.scale(-1, 1);
            if (turn.startsWith("TSL") || turn.startsWith("K")) {
                p.moveTo(-8, 28); p.lineTo(-8, 4); p.lineTo(22, -26);
                p.moveTo(2, -26); p.lineTo(22, -26); p.lineTo(22, -6);
            } else if (turn.startsWith("TSH")) {
                p.moveTo(-15, 28); p.lineTo(-15, -22); p.lineTo(24, 14);
                p.moveTo(4, 14); p.lineTo(24, 14); p.lineTo(24, -6);
            } else {
                p.moveTo(-16, 28); p.lineTo(-16, -15); p.lineTo(27, -15);
                p.moveTo(12, -30); p.lineTo(27, -15); p.lineTo(12, 0);
            }
        }
        c.drawPath(p, paint); c.restore();
    }
    private void line(Canvas c, String value, float y, float size, int color, boolean bold) {
        line(c, value, y, size, color, bold, 210);
    }
    private void line(Canvas c, String value, float y, float size, int color, boolean bold, float x) {
        text.setColor(color); text.setTextSize(size); text.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.CENTER); c.drawText(value, x, y, text);
    }
    private void block(Canvas c, String value, float y, float size, int color, boolean bold, int lines) {
        text.setColor(color); text.setTextSize(size); text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), text, 264)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        c.save(); c.translate(78, y); layout.draw(c); c.restore();
    }
}
