package com.androidfakerun.companion.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

/**
 * Hook 防检测通道的配置存取。
 *
 * <p>写入端：手机配套进程（{@code MockLocationReceiver} 收到桌面广播后调用
 * {@link #setEnabled}/{@link #setLocation}/{@link #clear}，数据落在配套应用的
 * SharedPreferences 里，全程不调用 {@code addTestProvider}，因此系统里不会产生
 * mock provider，也不会置起 {@code isFromMockProvider} 标记）。
 *
 * <p>读取端：LSPosed 注入到目标应用（以及可选的 system_server / GMS）进程后，
 * 通过导出的 {@code FakeRunProvider}（{@link #CONTENT_URI}）按需查询最新坐标，
 * 内存缓存 800ms，避免每次定位 getter 都走一次 Binder IO。
 */
public final class HookConfig {
    public static final String AUTHORITY = "com.androidfakerun.companion.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/location");

    public static final String COL_ENABLED = "enabled";
    public static final String COL_LAT = "lat";
    public static final String COL_LNG = "lng";
    public static final String COL_SPEED = "speed";
    public static final String COL_ACCURACY = "accuracy";
    public static final String COL_UPDATED_AT = "updated_at";

    private static final String PREFS = "fakerun_hook";
    private static final long CACHE_TTL_MS = 800L;

    private static volatile Snapshot sCache;
    private static volatile long sCacheAt;

    private HookConfig() {
    }

    // ---------- 写入端（配套进程） ----------

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(COL_ENABLED, enabled).apply();
        synchronized (HookConfig.class) {
            Snapshot cached = sCache;
            if (cached != null) sCache = new Snapshot(enabled, cached.lat, cached.lng,
                    cached.speed, cached.accuracy, cached.updatedAt);
        }
    }

    public static void setLocation(Context context, double lat, double lng, float speed) {
        prefs(context).edit()
                .putBoolean(COL_ENABLED, true)
                .putString(COL_LAT, Double.toString(lat))
                .putString(COL_LNG, Double.toString(lng))
                .putFloat(COL_SPEED, speed)
                .putFloat(COL_ACCURACY, 3.0f)
                .putLong(COL_UPDATED_AT, System.currentTimeMillis())
                .apply();
        sCache = null;
    }

    public static void clear(Context context) {
        prefs(context).edit()
                .putBoolean(COL_ENABLED, false)
                .putLong(COL_UPDATED_AT, System.currentTimeMillis())
                .apply();
        sCache = null;
    }

    public static boolean isHookEnabledLocal(Context context) {
        return prefs(context).getBoolean(COL_ENABLED, false);
    }

    // ---------- 读取端（任意被 Hook 的进程） ----------

    /** 配套进程内直接读 prefs；被 Hook 的外部进程走 {@link #get(ContentResolver)}。 */
    public static Snapshot getLocal(Context context) {
        SharedPreferences p = prefs(context);
        return new Snapshot(
                p.getBoolean(COL_ENABLED, false),
                parseDouble(p.getString(COL_LAT, "0"), 0),
                parseDouble(p.getString(COL_LNG, "0"), 0),
                p.getFloat(COL_SPEED, 0f),
                p.getFloat(COL_ACCURACY, 3.0f),
                p.getLong(COL_UPDATED_AT, 0));
    }

    public static Snapshot get(ContentResolver resolver) {
        long now = android.os.SystemClock.uptimeMillis();
        Snapshot cached = sCache;
        if (cached != null && now - sCacheAt < CACHE_TTL_MS) return cached;
        Snapshot fresh = query(resolver, cached);
        sCache = fresh;
        sCacheAt = now;
        return fresh;
    }

    private static Snapshot query(ContentResolver resolver, Snapshot fallback) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int iEnabled = cursor.getColumnIndex(COL_ENABLED);
                int iLat = cursor.getColumnIndex(COL_LAT);
                int iLng = cursor.getColumnIndex(COL_LNG);
                int iSpeed = cursor.getColumnIndex(COL_SPEED);
                int iAcc = cursor.getColumnIndex(COL_ACCURACY);
                int iTs = cursor.getColumnIndex(COL_UPDATED_AT);
                Snapshot s = new Snapshot(
                        iEnabled >= 0 && cursor.getInt(iEnabled) == 1,
                        iLat >= 0 ? cursor.getDouble(iLat) : 0,
                        iLng >= 0 ? cursor.getDouble(iLng) : 0,
                        iSpeed >= 0 ? cursor.getFloat(iSpeed) : 0f,
                        iAcc >= 0 ? cursor.getFloat(iAcc) : 3.0f,
                        iTs >= 0 ? cursor.getLong(iTs) : 0);
                if (s.enabled) return s;
                return s;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) try {
                cursor.close();
            } catch (Throwable ignored) {
            }
        }
        // Provider 不可用（配套被强杀/未授权查询）时沿用旧缓存，否则视为未启用。
        if (fallback != null) return fallback;
        return new Snapshot(false, 0, 0, 0f, 3.0f, 0);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final double lat;
        public final double lng;
        public final float speed;
        public final float accuracy;
        public final long updatedAt;

        public Snapshot(boolean enabled, double lat, double lng,
                        float speed, float accuracy, long updatedAt) {
            this.enabled = enabled;
            this.lat = lat;
            this.lng = lng;
            this.speed = speed;
            this.accuracy = accuracy;
            this.updatedAt = updatedAt;
        }

        /** 配置超过 10 分钟没更新视为过期，避免关机残留把正常定位一直盖住。 */
        public boolean isFresh() {
            return enabled && System.currentTimeMillis() - updatedAt < 10L * 60L * 1000L;
        }
    }
}
