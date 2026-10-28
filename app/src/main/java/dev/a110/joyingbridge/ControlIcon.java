package dev.a110.joyingbridge;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Resolution-independent icons with accessible labels supplied by their buttons. */
final class ControlIcon extends Drawable {
    static final int PLAY = 0, STOP = 1, SETTINGS = 2, BACK = 3;
    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ControlIcon(int kind, int color) { this.kind = kind; paint.setColor(color); }
    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setStyle(Paint.Style.FILL);
        if (kind == PLAY) {
            Path p = new Path(); p.moveTo(8, 4); p.lineTo(21, 12); p.lineTo(8, 20); p.close();
            canvas.drawPath(p, paint);
        } else if (kind == STOP) canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
        else {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND);
            if (kind == BACK) {
                canvas.drawLine(19, 12, 5, 12, paint); canvas.drawLine(5, 12, 12, 5, paint); canvas.drawLine(5, 12, 12, 19, paint);
            } else {
                canvas.drawCircle(12, 12, 6, paint); canvas.drawCircle(12, 12, 2, paint);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 12, 12);
                    canvas.drawLine(12, 2, 12, 5, paint); canvas.restore();
                }
            }
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
