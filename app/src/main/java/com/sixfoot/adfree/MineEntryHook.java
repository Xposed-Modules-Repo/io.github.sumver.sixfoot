package com.sixfoot.adfree;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「我的」页模块入口（目标：六只脚 v4.40.4 / versionCode 663）。
 *
 * 需求：把模块设置入口放进六只脚「我的」页，位于「最近浏览」下方，代替去桌面找模块图标。
 *
 * 逆向结构（`res/layout/pro_fragment_mine.xml`）：
 *
 *   CardView
 *     └─ LinearLayout（垂直，shape_white_corner_14）
 *          ├─ llMyReference  「我的循迹」
 *          ├─ llMyCollect    「我的收藏」
 *          └─ llMyRecent     「最近浏览」
 *
 * 因此这里在 `llMyRecent` 的父 LinearLayout 中、`llMyRecent` 之后插入一行同款条目：
 * 图标/字号/颜色/间距/高度全部从 `llMyRecent` 及其子 View 的运行期布局参数复制，
 * 跟随 App 自身样式，升级改版后也不会明显走样（资源 id 变化时仍可工作）。
 *
 * 点击行为：显式启动模块 App 的 {@link SettingsActivity}（AndroidManifest 已声明
 * exported=true）；从 Activity 上下文启动时留在当前任务，返回键可直接回到「我的」页。
 *
 * Hook 点：`ProMineFragment#onViewCreated(View,Bundle)`（视图创建后注入；视图重建会再次执行，
 * 通过 tag 去重）。找不到目标视图时只记录日志、跳过，不影响 App 运行。
 */
public final class MineEntryHook {

    private static final String CLS_MINE_FRAGMENT = "com.topgether.sixfootPro.biz.mine.ProMineFragment";
    private static final String ENTRY_TAG = "SixfootAdFree.mineEntry";
    private static final String ENTRY_TITLE = "模块设置";
    /** 模块设置页（类名固定为 com.sixfoot.adfree.SettingsActivity，applicationId 仅用于组件包名）。 */
    private static final String CLS_SETTINGS = "com.sixfoot.adfree.SettingsActivity";

    private static volatile boolean sAddedLogged;
    private static volatile boolean sErrorLogged;

    private MineEntryHook() {
    }

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_MINE_FRAGMENT, cl, "onViewCreated",
                    View.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                addEntry(param.thisObject);
                            } catch (Throwable t) {
                                logErrorOnce("add module entry failed: " + t);
                            }
                        }
                    });
            XposedInit.log("hooked ProMineFragment.onViewCreated (module settings entry)");
        } catch (Throwable t) {
            XposedInit.log("hook ProMineFragment.onViewCreated for entry failed: " + t);
        }
    }

    /** 在「最近浏览」下方插入模块设置入口（幂等）。 */
    private static void addEntry(Object fragment) {
        View recent = null;
        try {
            Object fieldValue = XposedHelpers.getObjectField(fragment, "llMyRecent");
            if (fieldValue instanceof View) {
                recent = (View) fieldValue;
            }
        } catch (Throwable ignored) {
            // 字段被裁剪/改名：放弃本次注入（只是少一个入口，不影响 App）
        }
        if (recent == null) {
            return;
        }

        View parentView = (View) recent.getParent();
        if (!(parentView instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) parentView;
        if (parent.findViewWithTag(ENTRY_TAG) != null) {
            return;
        }

        Context ctx = recent.getContext();
        if (ctx == null) {
            return;
        }

        LinearLayout row = buildRow(ctx, recent);
        int index = parent.indexOfChild(recent) + 1;
        parent.addView(row, index, copyLayoutParams(recent));

        if (!sAddedLogged) {
            sAddedLogged = true;
            XposedInit.log("module settings entry added below mine/recently-viewed");
        }
    }

    /** 按「最近浏览」行的现有样式构建新行（图标、字号、颜色、间距、高度全部复制）。 */
    private static LinearLayout buildRow(Context ctx, View template) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(ENTRY_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(ENTRY_TITLE);
        // 水波纹点击效果（与 App 内其它条目一致：?selectableItemBackground）
        TypedValue out = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                && out.resourceId != 0) {
            row.setBackgroundResource(out.resourceId);
        } else {
            Drawable bg = template.getBackground();
            if (bg != null) {
                row.setBackground(bg);
            }
        }
        row.setPadding(template.getPaddingLeft(), template.getPaddingTop(),
                template.getPaddingRight(), template.getPaddingBottom());

        View iconTemplate = childAt(template, 0);
        View titleTemplate = childAt(template, 1);
        View arrowTemplate = childAt(template, 2);

        if (iconTemplate != null) {
            ImageView icon = new ImageView(ctx);
            icon.setLayoutParams(copyLayoutParams(iconTemplate));
            int iconRes = drawableId(ctx, "icon_mine_setting");
            if (iconRes != 0) {
                icon.setImageResource(iconRes);
            } else if (iconTemplate instanceof ImageView
                    && ((ImageView) iconTemplate).getDrawable() != null) {
                // 兜底：沿用「最近浏览」的图标，保证至少有图标而不是空白
                icon.setImageDrawable(((ImageView) iconTemplate).getDrawable());
            }
            icon.setContentDescription(null);
            row.addView(icon);
        }

        TextView title = new TextView(ctx);
        title.setText(ENTRY_TITLE);
        if (titleTemplate instanceof TextView) {
            TextView t = (TextView) titleTemplate;
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize());
            title.setTextColor(t.getCurrentTextColor());
            title.setTypeface(t.getTypeface());
            title.setEllipsize(t.getEllipsize());
            title.setMaxLines(t.getMaxLines());
            title.setLayoutParams(copyLayoutParams(t));
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1f;
            title.setLayoutParams(lp);
        }
        row.addView(title);

        if (arrowTemplate != null) {
            ImageView arrow = new ImageView(ctx);
            arrow.setLayoutParams(copyLayoutParams(arrowTemplate));
            int arrowRes = drawableId(ctx, "arrow_right_v2");
            if (arrowRes != 0) {
                arrow.setImageResource(arrowRes);
            } else if (arrowTemplate instanceof ImageView
                    && ((ImageView) arrowTemplate).getDrawable() != null) {
                arrow.setImageDrawable(((ImageView) arrowTemplate).getDrawable());
            }
            arrow.setContentDescription(null);
            row.addView(arrow);
        }

        row.setOnClickListener(v -> launchSettings(v.getContext()));
        return row;
    }

    /**
     * 打开模块设置页。
     *
     * 不同版本的六只脚（例如 4.40.8）对跨应用启动的限制可能不同，因此按优先级尝试：
     *   1. 显式组件（Activity 上下文下同任务打开，返回键直接回「我的」）；
     *   2. 包管理器解析模块的 LAUNCHER Intent（模块入口就是 SettingsActivity，不依赖类名）；
     *   3. ACTION_MAIN + CATEGORY_LAUNCHER + setPackage。
     * 任一成功即返回；全部失败才提示。
     */
    private static void launchSettings(Context ctx) {
        if (ctx == null) {
            return;
        }
        Throwable last = null;

        // 1) 显式组件
        try {
            Intent intent = new Intent();
            intent.setClassName(Prefs.MODULE_PKG, CLS_SETTINGS);
            if (!(ctx instanceof Activity)) {
                // 非 Activity 上下文（极少数兜底路径）必须新任务启动
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
            return;
        } catch (Throwable t) {
            last = t;
        }

        // 2) 通过 PackageManager 解析模块的启动 Intent（不依赖具体类名）
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(Prefs.MODULE_PKG);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return;
            }
        } catch (Throwable t) {
            last = t;
        }

        // 3) 标准 LAUNCHER 隐式 Intent + 包名限定
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            main.setPackage(Prefs.MODULE_PKG);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(main);
            return;
        } catch (Throwable t) {
            last = t;
        }

        logErrorOnce("launch module settings failed: " + last);
        try {
            Toast.makeText(ctx, "未找到模块设置界面，请确认“六只脚去广告”已安装并启用",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static View childAt(View parent, int index) {
        return parent instanceof ViewGroup ? ((ViewGroup) parent).getChildAt(index) : null;
    }

    /** 复制布局参数（含 margin / weight / 宽高），避免新行与 App 自身样式不一致。 */
    private static ViewGroup.LayoutParams copyLayoutParams(View source) {
        ViewGroup.LayoutParams lp = source.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp);
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) lp);
        }
        if (lp != null) {
            return new LinearLayout.LayoutParams(lp.width, lp.height);
        }
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 按名称解析目标 App 的 drawable（不依赖编译期 R 常量）。 */
    private static int drawableId(Context ctx, String name) {
        try {
            return ctx.getResources().getIdentifier(name, "drawable", XposedInit.TARGET_PKG);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void logErrorOnce(String msg) {
        if (!sErrorLogged) {
            sErrorLogged = true;
            XposedInit.log(msg);
        }
    }
}
