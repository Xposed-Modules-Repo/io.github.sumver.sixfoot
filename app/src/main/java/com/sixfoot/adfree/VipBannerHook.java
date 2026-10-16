package com.sixfoot.adfree;

import android.os.Bundle;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「我的」页会员横幅（"开通六只脚会员"）拦截（目标：六只脚 v4.40.4 / versionCode 663）。
 *
 * 横幅本体：`ProMineFragment.mVipPayLayout`（ButterKnife @BindView），布局见
 * `res/layout/pro_fragment_mine.xml`（L93-106）——结构为：
 *
 *   <CardView marginTop=mine_card_gap(8dp)>          <!-- 无 id，横幅的外层卡片 -->
 *       <RelativeLayout id=mVipPayLayout             <!-- 横幅本体（高度随会员状态 63.5/88dp） -->
 *           标题图/描述/“免费试用|立即续费”按钮/>
 *   </CardView>
 *
 * 它是“我的”页竖向卡片流中的独立一张卡片（上方为统计卡，下方为“轨迹库”等功能卡，
 * 卡片之间统一以自身 marginTop=8dp 留距）。
 *
 * 【不留空的处理】只把横幅本体置 GONE 是不够的——外层 CardView 的 8dp 顶间距会残留，
 * 与下方卡片的 8dp 叠加成“双倍间距 + 空卡片位”。因此这里把横幅的**父 CardView 整体置 GONE**：
 * GONE 视图不参与 LinearLayout 测量，高度与 margin 一并消失，下方卡片自身的 8dp 顶间距接替，
 * 间距与其它卡片完全一致——彻底隐藏且不留空白。
 * （两步路模块那种“横幅藏在固定高度头部容器里、需要压缩头部高度”的场景不适用于六只脚：
 *   这里横幅是独立卡片，GONE 父卡片即无留空。）
 *
 * 展示时机（逆向）：`ProMineFragment.onViewCreated()` 尾部 `f(VipInfoManager.c())` 首绑，
 * 之后下拉刷新/数据回调会再次走 `f(VipInfoBean)`；横幅的可见性在 App 代码中从不切换，
 * 因此视图一旦隐藏即为终态（视图重建时 onViewCreated 会再次执行，重新隐藏）。
 *
 * Hook 点（任一命中即可隐藏；均在调用时检查开关，关闭开关并重启六只脚后恢复）：
 *   1. ProMineFragment#f(VipInfoBean)     —— 横幅数据绑定入口（首绑 + 刷新）；
 *   2. ProMineFragment#onViewCreated(...) —— 视图创建兜底。
 * 类名/方法名与 4.40.4 的混淆结果绑定；App 升级后若找不到 仅记录日志、自动跳过，不影响 App 运行。
 */
public final class VipBannerHook {

    private static final String CLS_MINE_FRAGMENT = "com.topgether.sixfootPro.biz.mine.ProMineFragment";
    private static final String CLS_VIP_INFO_BEAN =
            "com.topgether.sixfoot.lib.net.response.VipInfoBean";
    /** androidx.cardview.widget.CardView（横幅的父卡片；只隐藏该类型父容器，防误伤） */
    private static final String CLS_CARD_VIEW = "androidx.cardview.widget.CardView";
    /** com.topgether.sixfoot.R.id.mVipPayLayout 的 v4.40.4 常量值（动态解析失败时兜底） */
    private static final int ID_M_VIP_PAY_LAYOUT_FALLBACK = 0x7f0a06b3;

    private static volatile boolean sHiddenLogged;
    private static volatile boolean sErrorLogged;
    private static volatile int sBannerId = 0;

    private VipBannerHook() {
    }

    static void install(ClassLoader cl) {
        hookBannerBind(cl);
        hookViewCreated(cl);
    }

    /** 第 1 层：f(VipInfoBean)（横幅数据绑定入口，首绑与刷新都会经过）。 */
    private static void hookBannerBind(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_MINE_FRAGMENT, cl, "f", CLS_VIP_INFO_BEAN,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Prefs.isEnabled(Prefs.K_VIP_BANNER)) {
                                hideBanner(param.thisObject, "ProMineFragment.f");
                            }
                        }
                    });
            XposedInit.log("hooked ProMineFragment.f (mine vip banner bind point)");
        } catch (Throwable t) {
            XposedInit.log("hook ProMineFragment.f failed: " + t);
        }
    }

    /** 第 2 层：onViewCreated（视图创建兜底；视图重建后再次隐藏）。 */
    private static void hookViewCreated(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_MINE_FRAGMENT, cl, "onViewCreated",
                    View.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Prefs.isEnabled(Prefs.K_VIP_BANNER)) {
                                hideBanner(param.thisObject, "ProMineFragment.onViewCreated");
                            }
                        }
                    });
            XposedInit.log("hooked ProMineFragment.onViewCreated (mine vip banner view)");
        } catch (Throwable t) {
            XposedInit.log("hook ProMineFragment.onViewCreated failed: " + t);
        }
    }

    /**
     * 隐藏横幅：优先把横幅的父 CardView 整体置 GONE（height + margin 一并消失，不留空档）；
     * 父容器不是 CardView 时退化为隐藏横幅本体。
     */
    private static void hideBanner(Object fragment, String from) {
        try {
            View banner = null;
            // 路径 1：ButterKnife 字段（字段名在 dex 中保留）；字段缺失时继续走路径 2
            try {
                Object fieldValue = XposedHelpers.getObjectField(fragment, "mVipPayLayout");
                if (fieldValue instanceof View) {
                    banner = (View) fieldValue;
                }
            } catch (Throwable ignored) {
                // 字段被裁剪/改名：忽略，走资源 id 兜底
            }
            // 路径 2：按资源 id 在 fragment 视图中查找
            if (banner == null) {
                try {
                    Object root = XposedHelpers.callMethod(fragment, "getView");
                    if (root instanceof View) {
                        banner = ((View) root).findViewById(bannerId(fragment.getClass().getClassLoader()));
                    }
                } catch (Throwable ignored) {
                    // 视图尚未创建等场景：本次不处理，下次绑定/创建时再隐藏
                }
            }
            if (banner == null) {
                return;
            }

            View target = banner;
            Object parent = banner.getParent();
            if (parent instanceof View && CLS_CARD_VIEW.equals(parent.getClass().getName())) {
                // 关键：父 CardView 一起 GONE → 不留高度、不留 margin
                target = (View) parent;
            }
            if (target.getVisibility() != View.GONE) {
                target.setVisibility(View.GONE);
                if (!sHiddenLogged) {
                    sHiddenLogged = true;
                    XposedInit.log("vip banner hidden (parent card gone, no blank space left) via " + from);
                }
            }
        } catch (Throwable t) {
            if (!sErrorLogged) {
                sErrorLogged = true;
                XposedInit.log("hide vip banner failed: " + t);
            }
        }
    }

    /** 动态解析 R.id.mVipPayLayout，失败用 4.40.4 常量兜底（R 类被裁剪时仍可用）。 */
    private static int bannerId(ClassLoader cl) {
        int cached = sBannerId;
        if (cached != 0) {
            return cached;
        }
        int resolved = ID_M_VIP_PAY_LAYOUT_FALLBACK;
        try {
            Class<?> rId = XposedHelpers.findClass("com.topgether.sixfoot.R$id", cl);
            resolved = XposedHelpers.getStaticIntField(rId, "mVipPayLayout");
        } catch (Throwable ignored) {
            // R$id 缺失/被裁剪：使用 v4.40.4 常量
        }
        sBannerId = resolved;
        return resolved;
    }
}
