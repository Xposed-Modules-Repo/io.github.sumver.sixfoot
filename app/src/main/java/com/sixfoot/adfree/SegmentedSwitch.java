package com.sixfoot.adfree;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 分段选择器，复刻六只脚自身「行程上传设置」页（TrackSyncSettingsActivity）中的
 * 「自动 / 仅 Wi-Fi / 手动」选择样式：
 *
 *  - 外层：浅灰胶囊（#EEF0EF，圆角 17dp，内边距 2dp）；
 *  - 选中项：绿色胶囊（#20C77A，圆形），白色 10sp 中粗文字；
 *  - 未选中项：透明，灰色 10sp（#838789）文字；
 *  - 单项高度 30dp、左右内边距 9dp。
 *
 * 模块的每个开关都是布尔值，因此固定提供「关闭 / 开启」两段。
 */
public final class SegmentedSwitch extends LinearLayout {

    public interface OnSelectedListener {
        void onSelected(int index);
    }

    private static final int COLOR_CONTAINER = 0xFFEEF0EF;
    private static final int COLOR_SELECTED = 0xFF20C77A;
    private static final int COLOR_TEXT_SELECTED = 0xFFFFFFFF;
    private static final int COLOR_TEXT_NORMAL = 0xFF838789;

    private static final int ITEM_HEIGHT_DP = 30;
    private static final int ITEM_PADDING_H_DP = 9;
    private static final int TEXT_SIZE_SP = 10;

    private OnSelectedListener listener;
    private int selectedIndex = -1;

    public SegmentedSwitch(Context context) {
        this(context, null);
    }

    public SegmentedSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);
        setBackground(pill(COLOR_CONTAINER, dp(17)));
    }

    /** 设置分段文案（本模块固定两段：关闭 / 开启）。 */
    public void setOptions(String... labels) {
        removeAllViews();
        selectedIndex = -1;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = new TextView(getContext());
            item.setText(labels[i]);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
            item.setGravity(Gravity.CENTER);
            item.setIncludeFontPadding(false);
            item.setPadding(dp(ITEM_PADDING_H_DP), 0, dp(ITEM_PADDING_H_DP), 0);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, dp(ITEM_HEIGHT_DP)));
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> select(index, true));
            addView(item);
        }
        applySelection();
    }

    /** 选中第 index 段；notify=false 用于初始化（不触发保存回调）。 */
    public void setSelected(int index, boolean notify) {
        if (index < 0 || index >= getChildCount()) {
            return;
        }
        selectedIndex = index;
        applySelection();
        if (notify && listener != null) {
            listener.onSelected(index);
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnSelectedListener(OnSelectedListener listener) {
        this.listener = listener;
    }

    private void select(int index, boolean notify) {
        if (index == selectedIndex) {
            return;
        }
        setSelected(index, notify);
    }

    private void applySelection() {
        for (int i = 0; i < getChildCount(); i++) {
            TextView item = (TextView) getChildAt(i);
            boolean checked = i == selectedIndex;
            item.setBackground(checked ? pill(COLOR_SELECTED, dp(15)) : null);
            item.setTextColor(checked ? COLOR_TEXT_SELECTED : COLOR_TEXT_NORMAL);
            item.setSelected(checked);
            item.setTypeface(Typeface.create(checked ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
        }
    }

    private GradientDrawable pill(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setEnabled(enabled);
            child.setAlpha(enabled ? 1f : 0.4f);
        }
    }
}
