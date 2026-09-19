package com.locrec.app;

/**
 * WGS-84 / GCJ-02 / BD-09 坐标转换。
 *
 * 算法采用业界事实标准实现 (wandergis/coordtransform, 已联网核对公式)。
 * 数据集 fix 来自 LocationManager, 恒为 WGS-84; 而 BDLocation 契约是 BD-09、
 * 高德/腾讯 SDK 契约是 GCJ-02 —— 回放写入前不转换会系统性偏移数百米。
 * 出中国范围(标准实现的矩形粗判)不做加密偏移, 原样返回。
 *
 * 纯 Java 数学, 无依赖; 返回值统一为 {lng, lat}。
 */
public final class CoordTransform {

    private CoordTransform() {}

    private static final double X_PI = Math.PI * 3000.0 / 180.0;
    private static final double A = 6378245.0;                 // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323;   // 第一偏心率平方

    public static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lat, lng)) return new double[]{lng, lat};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    public static double[] gcj02ToBd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    public static double[] wgs84ToBd09(double lng, double lat) {
        double[] g = wgs84ToGcj02(lng, lat);
        return gcj02ToBd09(g[0], g[1]);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
