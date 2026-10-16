package com.sixfoot.adfree;

import android.app.AndroidAppHelper;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed / EdXposed 模块入口。
 *
 * 目标：com.topgether.sixfoot（六只脚）v4.40.4 (versionCode 663)
 * 功能：去除开屏广告（走 App 自带的"无广告跳过"路径，行为与真实无填充一致）。
 * 不伪造账号状态、不拦截网络请求、不动购买流程（详见 {@link SplashAdHook}）。
 *
 * 兼容性：使用经典 XposedBridge API（xposedminversion 82），
 * 不声明 xposedsharedprefs，配置通道为模块自带只读 ContentProvider（见 {@link Prefs}），
 * LSPosed / EdXposed 1.9.x 与 2.x 及以后版本通用。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "SixfootAdFree";
    public static final String TARGET_PKG = "com.topgether.sixfoot";
    /** 4.40.4 => versionCode 663；仅作提示，不做硬性拦截。 */
    public static final int TARGET_VERSION_CODE = 663;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        // 仅在主进程安装 Hook（跳过 :RecordService 等附属进程，开屏页只存在于主进程）
        if (lpparam.processName != null && lpparam.processName.contains(":")) {
            log("skip secondary process: " + lpparam.processName);
            return;
        }

        log("loaded target: " + lpparam.packageName + " process=" + lpparam.processName);

        // 先触发配置加载（异步、经模块自带只读 ContentProvider 读取，见 Prefs）：
        // 每个进程只加载一次；开关修改后需重启（强制停止）目标应用才生效
        try {
            Prefs.prime();
        } catch (Throwable t) {
            log("Prefs prime failed: " + t);
        }

        try {
            SplashAdHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("SplashAdHook install failed: " + t);
        }

        try {
            VipPopupHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("VipPopupHook install failed: " + t);
        }

        try {
            VipBannerHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("VipBannerHook install failed: " + t);
        }
    }

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    /** 延迟输出目标 App 版本（handleLoadPackage 时 Application 可能尚未创建）。 */
    private static volatile boolean sVersionLogged = false;

    @SuppressWarnings("deprecation")
    public static void logTargetVersionOnce() {
        if (sVersionLogged) {
            return;
        }
        sVersionLogged = true;
        try {
            android.content.Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return;
            }
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            log("target app version: " + pi.versionName + " (" + pi.versionCode + "), expected "
                    + TARGET_VERSION_CODE);
        } catch (Throwable ignored) {
        }
    }
}
