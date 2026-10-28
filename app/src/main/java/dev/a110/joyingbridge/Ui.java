package dev.a110.joyingbridge;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;

final class Ui {
    static final int BACKGROUND = Color.rgb(11, 16, 23), PANEL = Color.rgb(25, 35, 47);
    static final int TEXT = Color.rgb(239, 245, 250), MUTED = Color.rgb(147, 165, 184);
    static final int MUSIC = Color.rgb(69, 222, 194), NAV = Color.rgb(100, 180, 255);
    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static LinearLayout page(Context c) {
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BACKGROUND);
        int p = dp(c, 20); page.setPadding(p, p, p, p);
        return page;
    }
    static ImageButton iconButton(Context c, boolean accent) {
        ImageButton b = new ImageButton(c);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(accent ? MUSIC : PANEL); shape.setCornerRadius(dp(c, 18));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x446F8DA5), shape, null));
        int p = dp(c, 14); b.setPadding(p, p, p, p);
        return b;
    }
    static void styleSwitch(Switch s) {
        s.setTextColor(TEXT); s.setTextSize(18); s.setSwitchPadding(dp(s.getContext(), 24));
        s.setPadding(0, dp(s.getContext(), 18), 0, dp(s.getContext(), 18));
    }
    private Ui() { }
}
