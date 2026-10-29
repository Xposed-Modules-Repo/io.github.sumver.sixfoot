package com.sixfoot.adfree;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块设置界面。
 *
 * 视觉与交互沿用六只脚自身的「行程上传设置」页（TrackSyncSettingsActivity）：
 * 顶栏白底 48dp + 居中标题；页面底色 #F5F5F5；分组标题 11sp 灰；白色分组容器内每行
 * 「图标 + 15sp 标题 + 10sp 说明 + 右侧分段选择器」，行间 1dp 浅灰分割线；
 * 分段选择器为浅灰胶囊中的绿色选中胶囊（见 {@link SegmentedSwitch}）。
 *
 * 模块开关均为布尔值，因此每项显示「关闭 / 开启」两段，选中状态即写入本模块的
 * SharedPreferences（settings）。Hook 进程通过只读 ContentProvider 读取同一文件
 * （见 {@link Prefs}），修改后需重启（强制停止）六只脚才生效。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 单个开关项。 */
    private static final class SwitchItem {
        final String key;
        final String title;
        final String summary;
        final int iconRes;

        SwitchItem(String key, String title, String summary, int iconRes) {
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.iconRes = iconRes;
        }
    }

    private SharedPreferences prefs;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        prefs = Prefs.ui(this);
        seedDefaults();

        container = findViewById(R.id.container);
        buildContent();
    }

    /** 顶栏白底 + 深色状态栏图标，与 App 设置页保持一致。 */
    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.surface_white));
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.page_bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 首次打开时写入全部默认值（当前全部开关默认开启）。 */
    private void seedDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : Prefs.allKeys()) {
            if (!prefs.contains(key)) {
                editor.putBoolean(key, Prefs.defaultOf(key));
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private void buildContent() {
        container.removeAllViews();

        Map<String, List<SwitchItem>> groups = new LinkedHashMap<>();
        groups.put(getString(R.string.group_purify), Arrays.asList(
                new SwitchItem(Prefs.K_SPLASH,
                        getString(R.string.switch_splash),
                        getString(R.string.switch_splash_summary),
                        R.drawable.ic_setting_splash),
                new SwitchItem(Prefs.K_VIP_POPUP,
                        getString(R.string.switch_vip_popup),
                        getString(R.string.switch_vip_popup_summary),
                        R.drawable.ic_setting_popup)));
        groups.put(getString(R.string.group_mine), Arrays.asList(
                new SwitchItem(Prefs.K_VIP_BANNER,
                        getString(R.string.switch_vip_banner),
                        getString(R.string.switch_vip_banner_summary),
                        R.drawable.ic_setting_banner)));

        for (Map.Entry<String, List<SwitchItem>> entry : groups.entrySet()) {
            addSection(entry.getKey(), entry.getValue());
        }
        addFooter();
    }

    /** 分组标题 + 白色分组容器（内部为若干开关行，行间 1dp 分割线）。 */
    private void addSection(String title, List<SwitchItem> items) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        header.setTextColor(ContextCompat.getColor(this, R.color.text_section));
        header.setPadding(dp(20), dp(15), dp(20), dp(6));
        container.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_white));

        for (int i = 0; i < items.size(); i++) {
            SwitchItem item = items.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_option, card, false);
            ImageView icon = row.findViewById(R.id.iv_icon);
            TextView tvTitle = row.findViewById(R.id.tv_title);
            TextView tvSummary = row.findViewById(R.id.tv_summary);
            SegmentedSwitch selector = row.findViewById(R.id.segmented);

            icon.setImageResource(item.iconRes);
            tvTitle.setText(item.title);
            if (item.summary == null || item.summary.isEmpty()) {
                tvSummary.setVisibility(View.GONE);
            } else {
                tvSummary.setText(item.summary);
            }

            selector.setOptions(getString(R.string.option_off), getString(R.string.option_on));
            selector.setSelected(prefs.getBoolean(item.key, Prefs.defaultOf(item.key)) ? 1 : 0, false);
            selector.setOnSelectedListener(index -> {
                prefs.edit().putBoolean(item.key, index == 1).apply();
                // 注意：这里不能用 XposedInit.log —— 模块 App 进程里没有 Xposed API 类
                // （de.robv.android.xposed.* 为 compileOnly），引用会导致 NoClassDefFoundError。
                Log.i("SixfootAdFree", "settings: " + item.key + " -> " + (index == 1));
            });
            card.addView(row);

            if (i != items.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                params.leftMargin = dp(20);
                params.rightMargin = dp(20);
                card.addView(divider, params);
            }
        }

        container.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 页尾说明：与 App 设置页一致的小号浅灰注释。 */
    private void addFooter() {
        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_note));
        hint.setLineSpacing(dp(6), 1f);
        hint.setPadding(dp(20), dp(10), dp(20), 0);
        hint.setMovementMethod(LinkMovementMethod.getInstance());
        hint.setLinkTextColor(ContextCompat.getColor(this, R.color.segment_selected));
        Linkify.addLinks(hint, Linkify.WEB_URLS);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        container.addView(hint, params);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
