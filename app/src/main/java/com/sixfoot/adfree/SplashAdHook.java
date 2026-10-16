package com.sixfoot.adfree;

import android.app.Activity;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * 开屏广告拦截（目标：六只脚 v4.40.4 / versionCode 663）。
 *
 * 原理与三处 Hook 点均来自逆向分析（见项目根目录《六只脚_广告点分析.md》第 2 章；
 * 源码见 analysis/jadx-src/sources/…）：
 *
 *   开屏链路：SplashActivity.m2()（加载入口）
 *             → 读 AdsV6Manager.o()（开屏广告位 ID；空则走“接口并没有返回广告，直接进首页”）
 *             → GDTSplashIAD → GdtAdSplashLoader.e()（优量汇 fetchAdOnly → showAd）
 *
 * 三层防线（独立安装，互不阻塞；任一命中即可去掉开屏广告）：
 *   1. SplashActivity#m2()：命中时不再加载广告，直接走 App 自带的“无填充跳过”路径
 *      p2(0)（与“接口并没有返回广告”分支完全一致）进入主界面；
 *   2. AdsV6Manager#o()：开屏广告位 ID 取值器，命中时返回空串，使 m2() 自然走跳过分支
 *      （本层完全不改变页面调用顺序，行为最温和）；
 *   3. GdtAdSplashLoader#e(...)：优量汇加载器，最后一层兜底，直接不发起 SDK 请求
 *      （此层单独生效时，页面会等 App 自身 5 秒兜底超时后进入主界面）。
 *
 * 所有 Hook 在调用时检查开关（{@link Prefs#isEnabled}）：在模块设置里关闭开关并重启六只脚后
 * 恢复原始行为。类名/方法名与 4.40.4 的混淆结果绑定；App 升级后若找不到 仅记录日志、
 * 自动跳过，不影响 App 运行。
 */
public final class SplashAdHook {

    private static final String CLS_SPLASH_ACTIVITY =
            "com.topgether.sixfootPro.biz.splash.SplashActivity";
    private static final String CLS_ADS_MANAGER = "com.six.ads.AdsV6Manager";
    private static final String CLS_GDT_SPLASH_LOADER =
            "com.example.gdt_lib.splash.GdtAdSplashLoader";
    private static final String CLS_MY_SPLASH_LISTENER =
            "com.example.gdt_lib.splash.MySplashAdListener";

    private static volatile boolean sSkipLogged;

    private SplashAdHook() {
    }

    static void install(ClassLoader cl) {
        installSplashActivitySkip(cl);
        installAdsManagerEmptyId(cl);
        installLoaderSkip(cl);
    }

    /**
     * 第 1 层：SplashActivity#m2()（开屏广告加载入口）替换为直接跳过。
     * 预先解析 p2(int)（“延迟进入主页”）方法引用，替换体内直接调用；
     * 调用失败时回退原实现（此时第 2 层会把广告位 ID 置空，原实现同样走跳过分支）。
     */
    private static void installSplashActivitySkip(ClassLoader cl) {
        try {
            final Method p2 = XposedHelpers.findMethodExact(
                    CLS_SPLASH_ACTIVITY, cl, "p2", int.class);
            XposedHelpers.findAndHookMethod(CLS_SPLASH_ACTIVITY, cl, "m2", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Prefs.isEnabled(Prefs.K_SPLASH)) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    XposedInit.logTargetVersionOnce();
                    if (!sSkipLogged) {
                        sSkipLogged = true;
                        XposedInit.log("splash ad skipped (SplashActivity.m2 -> no-ad path)");
                    }
                    try {
                        // p2(0)：与“接口并没有返回广告，直接进首页”完全相同的路径
                        p2.invoke(param.thisObject, 0);
                    } catch (Throwable t) {
                        XposedInit.log("m2 skip failed, fallback to original: " + t);
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return null;
                }
            });
            XposedInit.log("hooked SplashActivity.m2 (splash ad load entry)");
        } catch (Throwable t) {
            XposedInit.log("hook SplashActivity.m2 failed: " + t);
        }
    }

    /**
     * 第 2 层：AdsV6Manager#o()（开屏广告位 ID 取值器）置空。
     * 置空后 m2() 会走“接口并没有返回广告，直接进首页”（p2(0)），全程不触碰页面调用顺序。
     */
    private static void installAdsManagerEmptyId(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_ADS_MANAGER, cl, "o", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Prefs.isEnabled(Prefs.K_SPLASH)) {
                        param.setResult("");
                    }
                }
            });
            XposedInit.log("hooked AdsV6Manager.o (splash ad unit id -> \"\")");
        } catch (Throwable t) {
            XposedInit.log("hook AdsV6Manager.o failed: " + t);
        }
    }

    /**
     * 第 3 层（兜底）：GdtAdSplashLoader#e(Activity, String, ViewGroup, MySplashAdListener, int)
     * ——优量汇开屏加载器，命中时直接不发起 SDK 请求（页面靠 App 自身 5 秒超时进入主界面）。
     */
    private static void installLoaderSkip(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_GDT_SPLASH_LOADER, cl, "e",
                    Activity.class, String.class, ViewGroup.class, CLS_MY_SPLASH_LISTENER, int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Prefs.isEnabled(Prefs.K_SPLASH)) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            XposedInit.log("splash ad skipped (GdtAdSplashLoader.e not called)");
                            return null;
                        }
                    });
            XposedInit.log("hooked GdtAdSplashLoader.e (gdt splash loader)");
        } catch (Throwable t) {
            XposedInit.log("hook GdtAdSplashLoader.e failed: " + t);
        }
    }
}
