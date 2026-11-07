package com.linglum.chat;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/** 二维码工具：生成「加好友」二维码 / 从扫码结果里抠出专属号 */
public final class QrUtil {

    private QrUtil() {
    }

    /** 二维码内容：linglum://add?wxid=xxx */
    public static String addLink(String wxid) {
        return "linglum://add?wxid=" + wxid;
    }

    /** 生成二维码位图（失败返回 null） */
    public static Bitmap make(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    px[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从扫码文本里取专属号：支持 linglum://add?wxid=xxx、带 wxid= 的链接、或直接一串专属号 */
    public static String parseWxid(String text) {
        if (text == null) return null;
        String t = text.trim();
        int i = t.indexOf("wxid=");
        if (i >= 0) {
            String v = t.substring(i + 5);
            int j = v.indexOf('&');
            if (j >= 0) v = v.substring(0, j);
            v = v.trim();
            return v.matches("^[A-Za-z0-9_]{3,32}$") ? v : null;
        }
        return t.matches("^[A-Za-z0-9_]{3,32}$") ? t : null;
    }
}
