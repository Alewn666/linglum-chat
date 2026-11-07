package com.linglum.chat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/** 把解密后的附件以 content:// 交给系统应用打开 */
public class AttachProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String seg = uri.getLastPathSegment();
        if (seg == null || seg.isEmpty() || seg.contains("/") || seg.contains("\\")
                || seg.contains("..") || seg.indexOf('\u0000') >= 0) {
            throw new java.io.FileNotFoundException("bad uri");
        }
        File f = new File(getContext().getCacheDir(), "attachments/" + seg);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
