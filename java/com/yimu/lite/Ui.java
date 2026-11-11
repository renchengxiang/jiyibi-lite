package com.yimu.lite;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 控件工厂：颜色常量 + 纯代码布局的公共构件。所有页面类共用。 */
public final class Ui {

    public static final int INK = 0xFF211F1B;
    public static final int INK2 = 0xFF6B665C;
    public static final int BG = 0xFFF6F4EF;
    public static final int CARD = 0xFFFFFDF9;
    public static final int LINE = 0xFFE6E1D6;
    public static final int ACCENT = 0xFFB4552D;
    public static final int GREEN = 0xFF2F6B4F;
    public static final int INK3 = 0xFF97907F;
    public static final int SEG_OFF = 0xFFECE7DC;
    public static final int ICON_BG = 0xFFF1ECE1;
    public static final int DEL = 0xFFB9B2A4;

    private Ui() { }

    public static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout card(Activity a, int color) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(a, 16));
        if (color == CARD) bg.setStroke(1, LINE);
        l.setBackground(bg);
        return l;
    }

    public static TextView bigAction(Activity a, String text, int bgColor, int textColor) {
        TextView t = tv(a, text, 19, textColor, true);
        t.setGravity(android.view.Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(a, 14));
        if (bgColor == CARD) bg.setStroke(2, LINE);
        t.setBackground(bg);
        t.setClickable(true);
        return t;
    }

    public static TextView segItem(Activity a, String text, boolean active) {
        TextView t = tv(a, text, 17, active ? Color.WHITE : INK2, active);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, dp(a, 12), 0, dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 999));
        bg.setColor(active ? INK : SEG_OFF);
        t.setBackground(bg);
        return t;
    }

    public static void styleSeg(Activity a, TextView t, boolean active) {
        t.setTextColor(active ? Color.WHITE : INK2);
        t.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 999));
        bg.setColor(active ? INK : SEG_OFF);
        t.setBackground(bg);
    }

    public static TextView tv(Activity a, String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static EditText amountInput(Activity a) {
        EditText et = new EditText(a);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("0.00");
        et.setTextSize(34);
        et.setTypeface(Typeface.DEFAULT_BOLD);
        et.setBackground(null);
        et.setTextColor(INK);
        return et;
    }

    public static View space(Activity a, int dp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(a, dp)));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int width, float weight, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                width == 0 ? 0 : width, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.rightMargin = rightMargin;
        return p;
    }

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static String fmt(double yuan) {
        return String.format(java.util.Locale.CHINA, "%,.2f", yuan);
    }

    public static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}
