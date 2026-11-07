package com.linglum.chat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * 极简文件共享 Provider：把缓存里的 APK 用 content:// 交给系统安装器。
 * （Android 7 起 file:// 会抛 FileUriExposedException，必须走 content://）
 */
public class ApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        // 只取纯文件名：%2F 解码后可能拼出 ../，一律拒绝（provider 不导出，这是第二道闸）
        String seg = uri.getLastPathSegment();
        if (seg == null || seg.isEmpty() || seg.contains("/") || seg.contains("\\")
                || seg.contains("..") || seg.indexOf('\u0000') >= 0) {
            throw new java.io.FileNotFoundException("bad uri");
        }
        File f = new File(getContext().getCacheDir(), "update/" + seg);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
