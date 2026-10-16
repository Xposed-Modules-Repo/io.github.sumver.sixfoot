package com.sixfoot.adfree;

import android.app.Dialog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * VIP 推广弹窗拦截（目标：六只脚 v4.40.4 / versionCode 663）。
 *
 * 现象：打开 App 进入首页后（约 1 秒），有时会弹出一张"开通会员 / 立即开通"的图片弹窗。
 * 这是服务端下发（GlobalInfo.dialogInfo，showType="vip_pay"，默认 96 小时展示间隔、
 * 非 VIP 且非首次安装才展示）的推广图弹窗，与是否点击过无关，属于常驻变现位。
 *
 * 逆向链路（见项目根目录《六只脚_广告点分析.md》第 3.5 节）：
 *   ProMainActivity.f(VipInfoBean)   // 首页 VIP 信息加载完成回调
 *     → t2(VipInfoBean)              // 首次安装/间隔/ VIP 判断，postDelayed(1s)
 *       → I2()                       // 唯一show点：new VipDialog(this).show()
 *          VipDialog = 图片弹窗（dialog_vip.xml：iv_pay_vip 推广图 + iv_close 关闭钮），
 *          图片由服务端 dialogInfo.pic 下发（即包含"开通会员/立即开通"字样的那张图）。
 *
 * 三层防线（独立安装，互不阻塞；任一命中即可不弹窗）：
 *   1. ProMainActivity#I2()                    —— 弹窗展示唯一入口，命中时直接不展示；
 *   2. ProMainActivity#t2(VipInfoBean)         —— 弹窗调度器，命中时不再 postDelayed；
 *   3. Dialog#show()（按实例类名精确过滤）      —— 兜底：任何代码路径展示 VipDialog 都会被拦下
 *      （全局方法上挂 hook，但仅对 com.topgether.sixfootPro.ui.VipDialog 实例生效，
 *        其他弹窗完全不受影响）。
 *
 * 所有 Hook 在调用时检查开关（{@link Prefs#isEnabled}）：在模块设置里关闭开关并重启六只脚后
 * 恢复原始行为。类名/方法名与 4.40.4 的混淆结果绑定；App 升级后若找不到 仅记录日志、
 * 自动跳过，不影响 App 运行。
 */
public final class VipPopupHook {

    private static final String CLS_MAIN_ACTIVITY =
            "com.topgether.sixfootPro.biz.home.ProMainActivity";
    private static final String CLS_VIP_INFO_BEAN =
            "com.topgether.sixfoot.lib.net.response.VipInfoBean";
    private static final String CLS_VIP_DIALOG = "com.topgether.sixfootPro.ui.VipDialog";

    private static volatile boolean sSkipLogged;

    private VipPopupHook() {
    }

    static void install(ClassLoader cl) {
        installShowPointSkip(cl);
        installSchedulerSkip(cl);
        installDialogShowGuard(cl);
    }

    /**
     * 第 1 层：ProMainActivity#I2()（弹窗展示唯一入口）替换为直接不展示。
     */
    private static void installShowPointSkip(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_MAIN_ACTIVITY, cl, "I2", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Prefs.isEnabled(Prefs.K_VIP_POPUP)) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    logSkipOnce("vip popup skipped (ProMainActivity.I2)");
                    return null;
                }
            });
            XposedInit.log("hooked ProMainActivity.I2 (vip popup show point)");
        } catch (Throwable t) {
            XposedInit.log("hook ProMainActivity.I2 failed: " + t);
        }
    }

    /**
     * 第 2 层：ProMainActivity#t2(VipInfoBean)（弹窗调度器）命中时跳过（不再 1 秒后弹出）。
     */
    private static void installSchedulerSkip(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_MAIN_ACTIVITY, cl, "t2", CLS_VIP_INFO_BEAN,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Prefs.isEnabled(Prefs.K_VIP_POPUP)) {
                                logSkipOnce("vip popup skipped (ProMainActivity.t2)");
                                param.setResult(null);
                            }
                        }
                    });
            XposedInit.log("hooked ProMainActivity.t2 (vip popup scheduler)");
        } catch (Throwable t) {
            XposedInit.log("hook ProMainActivity.t2 failed: " + t);
        }
    }

    /**
     * 第 3 层（兜底）：Dialog#show() 全局方法上挂 hook，仅当实例是 VipDialog 且开关开启时
     * 直接跳过展示；其他 Dialog 完全不受影响。
     */
    private static void installDialogShowGuard(ClassLoader cl) {
        try {
            Class<?> vipDialogClass = XposedHelpers.findClass(CLS_VIP_DIALOG, cl);
            // show() 未在 VipDialog 中重写，沿继承链找到声明处（android.app.Dialog）
            Method show = null;
            for (Class<?> c = vipDialogClass; c != null && show == null; c = c.getSuperclass()) {
                try {
                    show = c.getDeclaredMethod("show");
                } catch (NoSuchMethodException ignored) {
                    // 继续向父类查找
                }
            }
            if (show == null) {
                XposedInit.log("hook Dialog.show guard failed: show() not found");
                return;
            }
            XposedBridge.hookMethod(show, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    // 只拦 VipDialog 实例；其余 Dialog 一律放行（不改变默认行为）
                    if (self == null || !CLS_VIP_DIALOG.equals(self.getClass().getName())) {
                        return;
                    }
                    if (Prefs.isEnabled(Prefs.K_VIP_POPUP)) {
                        logSkipOnce("vip popup skipped (VipDialog.show guard)");
                        param.setResult(null);
                    }
                }
            });
            XposedInit.log("hooked Dialog.show (vip dialog guard, " + show.getDeclaringClass().getName() + ")");
        } catch (Throwable t) {
            XposedInit.log("hook Dialog.show guard failed: " + t);
        }
    }

    /** 跳过日志只输出一次（多个拦截点重复触发时不刷屏）。 */
    private static void logSkipOnce(String msg) {
        if (!sSkipLogged) {
            sSkipLogged = true;
            XposedInit.log(msg);
        }
    }
}
