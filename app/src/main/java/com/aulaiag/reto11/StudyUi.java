package com.aulaiag.reto11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StudyUi {
    static final int BACKGROUND = 0xFFF8F7F0;
    static final int INK = 0xFF24321E;
    static final int ACCENT = 0xFF94B43B;
    static final int GREEN = 0xFF466B3F;
    static final int MUTED = 0xFF565A5C;
    static final int BORDER = 0xFFD8DBCF;
    static final int LIGHT = 0xFFEEF1E6;
    static final int QUESTION = 0xFFE8EFD7;
    static final int PAPER = 0xFFFFFEFA;
    static final int ERROR = 0xFF76232F;
    static final int ON_DARK_MUTED = 0xFFDCE4D7;
    private static final int PRESSED = 0x24466B3F;

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
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    TextView heading(CharSequence contents, int size) {
        TextView heading = text(contents, size, INK, true);
        markHeading(heading);
        return heading;
    }

    TextView display(CharSequence contents, int size, int color) {
        TextView title = text(contents, size, color, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        title.setLetterSpacing(-0.035f);
        title.setIncludeFontPadding(false);
        markHeading(title);
        return title;
    }

    private static void markHeading(TextView heading) {
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
    }

    Button button(CharSequence contents, boolean primary, Runnable onClick) {
        int fill = primary ? ACCENT : PAPER;
        return control(contents, fill, primary ? 0 : BORDER, 13, onClick);
    }

    Button shortcut(CharSequence contents, Runnable onClick) {
        Button button = control(contents, LIGHT, BORDER, 24, onClick);
        button.setPadding(dp(4), dp(5), dp(4), dp(5));
        return button;
    }

    Button accessButton(CharSequence contents, Runnable onClick) {
        Button button = control(contents, PAPER, 0, 16, onClick);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(15);
        button.setMinHeight(dp(54));
        button.setMinimumHeight(dp(54));
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        arrow(button, false);
        return button;
    }

    void arrow(Button button, boolean external) {
        button.setCompoundDrawablePadding(dp(12));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, new Arrow(external), null);
    }

    private Button control(CharSequence contents, int fill, int border, int radius, Runnable onClick) {
        Button button = new Button(context);
        button.setText(contents);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, INK}));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESSED),
                states(fill, border, radius), shape(Color.WHITE, 0, radius)));
        button.setPadding(dp(12), dp(5), dp(12), dp(5));
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    Drawable field() {
        return states(PAPER, BORDER, 12);
    }

    private Drawable states(int fill, int border, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(LIGHT, BORDER, radius));
        states.addState(new int[]{android.R.attr.state_focused}, shape(fill, GREEN, radius));
        states.addState(new int[]{}, shape(fill, border, radius));
        return states;
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

    void rule(LinearLayout layout, int color, int top) {
        View line = new View(context);
        line.setBackgroundColor(color);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(top);
        layout.addView(line, params);
    }

    void block(LinearLayout layout, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        layout.addView(view, params);
    }

    private final class Arrow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean external;

        Arrow(boolean external) {
            this.external = external;
            paint.setColor(INK);
            paint.setStrokeWidth(dp(1.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float left = bounds.left + bounds.width() * 0.28f;
            float right = bounds.left + bounds.width() * 0.72f;
            float top = bounds.top + bounds.height() * 0.28f;
            float bottom = bounds.top + bounds.height() * 0.72f;
            if (external) {
                canvas.drawLine(left, bottom, right, top, paint);
                canvas.drawLine(left, top, right, top, paint);
                canvas.drawLine(right, top, right, bottom, paint);
            } else {
                float middle = bounds.exactCenterY();
                float direction = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                float tip = bounds.exactCenterX() + (right - left) * direction / 2;
                float start = bounds.exactCenterX() - (right - left) * direction / 2;
                canvas.drawLine(start, top, tip, middle, paint);
                canvas.drawLine(tip, middle, start, bottom, paint);
            }
        }

        @Override public int getIntrinsicWidth() { return dp(18); }
        @Override public int getIntrinsicHeight() { return dp(18); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
