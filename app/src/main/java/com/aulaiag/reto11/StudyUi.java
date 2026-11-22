package com.aulaiag.reto11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

final class StudyUi {
    static final int BACKGROUND = Color.rgb(245, 248, 239);
    static final int INK = Color.rgb(22, 59, 49);
    static final int GREEN = Color.rgb(24, 73, 60);
    static final int MUTED = Color.rgb(70, 96, 88);
    static final int BORDER = Color.rgb(185, 210, 193);
    static final int LIGHT = Color.rgb(231, 239, 220);
    static final int QUESTION = Color.rgb(219, 237, 199);
    static final int PAPER = Color.rgb(253, 254, 252);
    static final int ERROR = Color.rgb(117, 48, 37);
    private static final int PRESSED = Color.argb(35, 41, 127, 102);

    private final Context context;

    StudyUi(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(CharSequence contents, float size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(contents);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    TextView heading(CharSequence contents, int size) {
        TextView heading = text(contents, size, INK, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        return heading;
    }

    Button button(CharSequence contents, boolean primary, Runnable onClick) {
        Button button = new Button(context);
        button.setText(contents);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? LIGHT : MUTED, primary ? Color.WHITE : GREEN}));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(5), dp(12), dp(5));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESSED),
                shape(primary ? GREEN : PAPER, primary ? 0 : BORDER, 17), null));
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    GradientDrawable shape(int fill, int border, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (border != 0) {
            background.setStroke(dp(1), border);
        }
        return background;
    }

    void block(LinearLayout layout, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        layout.addView(view, params);
    }

    void spacer(LinearLayout layout, int height) {
        layout.addView(new Space(context), new LinearLayout.LayoutParams(1, dp(height)));
    }
}


