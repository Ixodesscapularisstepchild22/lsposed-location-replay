# Xposed 入口类与回调禁止混淆/移除
-keep class com.locrec.app.MainHook { *; }
-keep class * implements de.robv.android.xposed.XC_MethodHook
-keep class * implements android.location.LocationListener
-dontwarn de.robv.android.xposed.**
