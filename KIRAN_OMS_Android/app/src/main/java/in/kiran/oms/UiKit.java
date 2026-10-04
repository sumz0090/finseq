package in.kiran.oms;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiKit {
    static final int BG = Color.rgb(246,248,252);
    static final int NAVY = Color.rgb(15,23,42);
    static final int BLUE = Color.rgb(37,99,235);
    static final int TEXT = Color.rgb(30,41,59);
    static final int MUTED = Color.rgb(100,116,139);
    static final int BORDER = Color.rgb(226,232,240);
    static final int GREEN = Color.rgb(22,163,74);
    static final int AMBER = Color.rgb(217,119,6);
    static final int RED = Color.rgb(220,38,38);

    private UiKit() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp * 3f);
        if (strokeColor != Color.TRANSPARENT) d.setStroke(1, strokeColor);
        return d;
    }

    static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView chip(Context c, String text, int bg, int fg) {
        TextView t = UiKit.text(c, text, 11, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c,10), dp(c,5), dp(c,10), dp(c,5));
        t.setBackground(rounded(bg, 10, Color.TRANSPARENT));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c,14), dp(c,13), dp(c,14), dp(c,13));
        l.setBackground(rounded(Color.WHITE, 6, BORDER));
        l.setElevation(dp(c,1));
        return l;
    }

    static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c,h)));
        return v;
    }

    static int statusColor(String status) {
        if ("In Production".equalsIgnoreCase(status)) return AMBER;
        if ("Ready".equalsIgnoreCase(status)) return BLUE;
        if ("Dispatched".equalsIgnoreCase(status)) return GREEN;
        if ("Cancelled".equalsIgnoreCase(status)) return Color.rgb(127,29,29);
        return RED;
    }
}
