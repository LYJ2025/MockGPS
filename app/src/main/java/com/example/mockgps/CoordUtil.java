package com.example.mockgps;

/**
 * 坐标系换算工具。
 *
 * <p>GPS 上报的坐标是 WGS-84；国内合法出版的地图（本项目用的高德瓦片）是 GCJ-02（火星坐标）。
 * 两者在同一地点相差约几百米，所以：
 * <ul>
 *   <li>把 WGS-84 坐标画到高德瓦片上：{@link #wgs84ToGcj02}</li>
 *   <li>把高德瓦片上的点换算成真实 GPS 坐标：{@link #gcj02ToWgs84}</li>
 * </ul>
 *
 * <p>App 对外（输入、输出、上报给系统的虚拟位置）一律使用 WGS-84。
 */
public final class CoordUtil {

    private static final double PI = 3.1415926535897932384626;
    private static final double AXIS = 6378245.0;            // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323; // 偏心率平方

    private CoordUtil() {
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** WGS-84 → GCJ-02，返回 {纬度, 经度} */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** GCJ-02 → WGS-84，迭代逼近，精度可达厘米级。返回 {纬度, 经度} */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double wLat = lat, wLng = lng;
        for (int i = 0; i < 5; i++) {
            double[] g = wgs84ToGcj02(wLat, wLng);
            wLat += lat - g[0];
            wLng += lng - g[1];
        }
        return new double[]{wLat, wLng};
    }
}
