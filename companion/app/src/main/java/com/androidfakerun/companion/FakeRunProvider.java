package com.androidfakerun.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.androidfakerun.companion.hook.HookConfig;

/**
 * 只读导出 Provider，把配套进程里的 Hook 坐标共享给被 LSPosed 注入的目标应用进程。
 *
 * <p>URI：{@code content://com.androidfakerun.companion.config/location}，
 * 列定义见 {@link HookConfig}。无须任何权限即可查询；增删改一律拒绝，
 * 写入只允许配套进程内部经 {@link HookConfig} 落 prefs。
 */
public final class FakeRunProvider extends ContentProvider {
    private static final int CODE_LOCATION = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(HookConfig.AUTHORITY, "location", CODE_LOCATION);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != CODE_LOCATION) return null;
        HookConfig.Snapshot s = HookConfig.getLocal(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{
                HookConfig.COL_ENABLED, HookConfig.COL_LAT, HookConfig.COL_LNG,
                HookConfig.COL_SPEED, HookConfig.COL_ACCURACY, HookConfig.COL_UPDATED_AT});
        cursor.addRow(new Object[]{
                s.enabled ? 1 : 0, s.lat, s.lng, s.speed, s.accuracy, s.updatedAt});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.fakerun.location";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
