package com.example.mockgps;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹持久化：经纬度 + 备注，存本地 SharedPreferences（JSON 数组）。
 */
public final class FavoriteStore {

    /** 一条收藏记录 */
    public static class Favorite {
        public String name;
        public double lat;
        public double lng;

        public Favorite(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final String PREF = "favorites";
    private static final String KEY = "list";

    private FavoriteStore() {
    }

    /** 读取收藏列表；数据损坏时返回空列表，不影响主功能 */
    public static List<Favorite> load(Context ctx) {
        List<Favorite> list = new ArrayList<>();
        String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Favorite(o.optString("name", ""),
                        o.getDouble("lat"), o.getDouble("lng")));
            }
        } catch (Exception ignored) {
            // 忽略损坏数据
        }
        return list;
    }

    public static void save(Context ctx, List<Favorite> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Favorite f : list) {
                JSONObject o = new JSONObject();
                o.put("name", f.name);
                o.put("lat", f.lat);
                o.put("lng", f.lng);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
            // JSONException 理论上不会触发
        }
    }
}
