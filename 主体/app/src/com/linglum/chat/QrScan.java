package com.linglum.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;

/**
 * 扫一扫：打开相机实时找二维码，识别到就把内容回传给 MainActivity。
 * 相机在部分机型/权限被拒时用不了 —— 所以额外提供「从相册选一张二维码」的兜底。
 */
public class QrScan extends Activity implements SurfaceHolder.Callback, android.hardware.Camera.PreviewCallback {

    private static final int REQ_CAM = 71;
    private static final int REQ_PICK = 72;

    private android.hardware.Camera cam;
    private SurfaceView sv;
    private TextView hint;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private volatile boolean done;
    private int frame;
    private int displayDeg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.getHolder().addCallback(this);

        // 底部：提示在上、按钮在下，绝不重叠
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(0, dp(10), 0, dp(22));
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(0x99000000);
        bottom.setBackground(bg2);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottom, blp);

        hint = new TextView(this);
        hint.setText("把对方的二维码放进框里");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), 0, dp(16), dp(10));
        bottom.addView(hint);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), 0, dp(20), 0);
        bottom.addView(bar);

        Button pick = new Button(this);
        pick.setText("从相册选二维码");
        pick.setAllCaps(false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                startActivityForResult(i, REQ_PICK);
            }
        });
        bar.addView(pick, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(cancel);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else if (req == REQ_CAM) {
            hint.setText("没有相机权限：可以从相册选二维码");
        }
    }

    // ---------------- 相机 ----------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        applyOrientation();     // 旋转屏幕后重算，平板横屏也能正立
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera() {
        if (cam != null) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            cam = android.hardware.Camera.open();
            android.hardware.Camera.Parameters p = cam.getParameters();
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            try {
                p.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } catch (Exception ignored) {
            }
            cam.setParameters(p);

            applyOrientation();
            cam.setPreviewDisplay(sv.getHolder());
            cam.setPreviewCallback(this);
            cam.startPreview();
        } catch (Throwable t) {
            hint.setText("相机打不开：" + t.getMessage() + "\n可以从相册选二维码");
            releaseCamera();
        }
    }

    /** 按「摄像头朝向 + 屏幕方向」算预览角度（竖屏手机 = 90，横屏平板 = 0/180） */
    private void applyOrientation() {
        try {
            android.hardware.Camera.CameraInfo ci = new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(0, ci);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int deg = 0;
            if (rot == android.view.Surface.ROTATION_90) deg = 90;
            else if (rot == android.view.Surface.ROTATION_180) deg = 180;
            else if (rot == android.view.Surface.ROTATION_270) deg = 270;
            int result;
            if (ci.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (ci.orientation + deg) % 360;
                result = (360 - result) % 360;
            } else {
                result = (ci.orientation - deg + 360) % 360;
            }
            displayDeg = result;
            if (cam != null) cam.setDisplayOrientation(result);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyOrientation();
    }

    private void releaseCamera() {
        try {
            if (cam != null) {
                cam.setPreviewCallback(null);
                cam.stopPreview();
                cam.release();
            }
        } catch (Throwable ignored) {
        }
        cam = null;
    }

    @Override
    public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
        if (busy || done) return;
        if ((frame++ % 4) != 0) return;          // 每 4 帧解一次，别把 CPU 打满
        busy = true;
        int w, h;
        try {
            android.hardware.Camera.Size s = camera.getParameters().getPreviewSize();
            w = s.width;
            h = s.height;
        } catch (Throwable t) {
            busy = false;
            return;
        }
        final byte[] buf = data;
        final int fw = w, fh = h;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.google.zxing.LuminanceSource base =
                            new PlanarYUVLuminanceSource(buf, fw, fh, 0, 0, fw, fh, false);
                    String got = tryDecode(base);
                    if (got == null) {
                        // 预览转了 90°，buffer 还是传感器方向 → 按显示角度再试
                        int times = ((360 - displayDeg) % 360) / 90;
                        com.google.zxing.LuminanceSource s2 = base;
                        for (int i = 0; i < times; i++) s2 = s2.rotateCounterClockwise();
                        got = tryDecode(s2);
                    }
                    if (got == null) {
                        got = tryDecode(base.rotateCounterClockwise());   // 兜底：再试 90°
                    }
                    if (got != null) finishWith(got);
                } catch (Throwable ignored) {
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    // ---------------- 从相册识别 ----------------

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PICK && result == RESULT_OK && data != null && data.getData() != null) {
            decodeImage(data.getData());
        }
    }

    private void decodeImage(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();
                    if (bmp == null) throw new Exception("图片读不出来");
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(w, h, px)));
                    com.google.zxing.Result r = reader.decodeWithState(bb);
                    if (r != null && r.getText() != null) {
                        finishWith(r.getText());
                    } else {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                hint.setText("这张图里没认出二维码，换一张试试");
                            }
                        });
                    }
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hint.setText("识别失败：" + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- 结果 ----------------

    /** 试解一次，成功返回文本，失败返回 null（不抛异常） */
    private String tryDecode(com.google.zxing.LuminanceSource src) {
        try {
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            com.google.zxing.Result r = reader.decodeWithState(bmp);
            return r == null ? null : r.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void finishWith(final String text) {
        if (done) return;
        done = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent();
                i.putExtra("text", text);
                setResult(RESULT_OK, i);
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }
}
