package com.linglum.chat;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * 热更新加载器（壳的一部分，**不参与热更新** —— 它必须足够稳）。
 *
 * 目录：filesDir/patch/classes.dex + classes.dex.sig
 * 校验：ECDSA(P-256) 验签（公钥烧在下面，私钥只在 NAS 上，绝不下发）
 * 回滚：验签失败 / 加载异常 / 连续两次启动失败 → 删除补丁，回落到包里自带的 ChatApp
 *
 * ⚠️ 跨 ClassLoader 的类必须是 public（补丁里的 ChatApp 与壳不在同一个 Loader，
 *    包级私有会变成 IllegalAccessError）。
 */
public final class HotLoader {

    public static final String DIR = "patch";
    public static final String PREFS = "hotfix";

    /** 补丁签名公钥（ECDSA P-256，X.509/SPKI，Base64） */
    private static final String PUBKEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZsAKhn4O8zKiLkfOFX2dL1m0VPVNxy/KUwfH4Ge7Wfm3EassYUaYQ/uErbU6BGf+Dg2goc37SStl2Q36v4wt0Q==";

    private HotLoader() {
    }

    /** 包里自带的实现（兜底，也是没补丁时的默认实现） */
    public static AppCore loadBundled(Activity a) {
        return new ChatApp(a);
    }

    /** 优先用补丁，失败/没有就回落 */
    public static AppCore load(Activity a) {
        File dex = new File(new File(a.getFilesDir(), DIR), "classes.dex");
        if (!dex.isFile()) return loadBundled(a);

        SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int attempts = sp.getInt("attempts", 0);
        if (attempts >= 2) {                       // 连续两次没能正常启动 → 弃用
            discard(a, "连续启动失败 " + attempts + " 次");
            return loadBundled(a);
        }
        try {
            if (!verify(dex)) {
                discard(a, "补丁验签失败");
                return loadBundled(a);
            }
            sp.edit().putInt("attempts", attempts + 1).apply();   // 先记一笔，启动成功再清零
            ClassLoader parent = new Filter(a.getClassLoader());
            ClassLoader cl = new dalvik.system.DexClassLoader(
                    dex.getAbsolutePath(), a.getCodeCacheDir().getAbsolutePath(), null, parent);
            Class<?> c = cl.loadClass("com.linglum.chat.ChatApp");
            Object o = c.getConstructor(Activity.class).newInstance(a);
            if (!(o instanceof AppCore)) {
                discard(a, "补丁类型不对");
                return loadBundled(a);
            }
            return (AppCore) o;
        } catch (Throwable t) {
            discard(a, "补丁加载失败: " + t);
            return loadBundled(a);
        }
    }

    /** 启动成功：清零失败计数 */
    public static void markBootOk(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("attempts", 0).putBoolean("boot_ok", true).apply();
    }

    /** 安装补丁：临时文件 → 只读 → 原子改名（Android 14 要求可动态加载的 dex 只读） */
    public static void installPatch(Context c, byte[] dexBytes, byte[] sigBytes,
                                   int version, String notes) throws Exception {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("补丁目录创建失败");

        File tmpDex = new File(dir, "classes.dex.tmp");
        FileOutputStream os = new FileOutputStream(tmpDex);
        os.write(dexBytes);
        os.flush();
        os.close();
        File tmpSig = new File(dir, "classes.dex.sig.tmp");
        FileOutputStream os2 = new FileOutputStream(tmpSig);
        os2.write(sigBytes);
        os2.flush();
        os2.close();

        File dex = new File(dir, "classes.dex");
        File sig = new File(dir, "classes.dex.sig");
        if (dex.exists()) dex.delete();
        if (sig.exists()) sig.delete();
        if (!tmpDex.renameTo(dex)) throw new Exception("补丁落盘失败");
        if (!tmpSig.renameTo(sig)) throw new Exception("签名落盘失败");
        dex.setReadOnly();
        sig.setReadOnly();

        if (!verify(dex)) {                         // 装完再验一次，不通过就当没装
            dex.delete();
            sig.delete();
            throw new Exception("补丁验签失败（下载不完整？）");
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("version", version)
                .putInt("attempts", 0)
                .putString("notes", notes == null ? "" : notes)
                .putString("last_error", "")
                .apply();
    }

    public static int appliedVersion(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("version", 0);
    }

    public static String lastError(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_error", "");
    }

    /** 手动回滚：删补丁 → 下次启动用包里自带的版本 */
    public static void discardPatch(Context c, String why) {
        discard(c, why);
    }

    private static void discard(Context c, String why) {
        try {
            File d = new File(c.getFilesDir(), DIR);
            File[] fs = d.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    f.setWritable(true);
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("version", 0)
                .putInt("attempts", 0)
                .putString("last_error", why == null ? "" : why)
                .apply();
    }

    /** ECDSA 验签（对 dex 原始字节） */
    private static boolean verify(File dex) {
        File sigFile = new File(dex.getParentFile(), "classes.dex.sig");
        if (!dex.isFile() || !sigFile.isFile()) return false;
        try {
            byte[] data = readAll(dex);
            byte[] sig = readAll(sigFile);
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(PUBKEY_B64, Base64.DEFAULT)));
            Signature sg = Signature.getInstance("SHA256withECDSA");
            sg.initVerify(pk);
            sg.update(data);
            return sg.verify(sig);
        } catch (Throwable t) {
            return false;
        }
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * 只让 `com.linglum.chat.ChatApp*` 落到补丁 dex 里；
     * 其余类（Api/Crypto/R/MainActivity/ChatService…）一律用 APK 自带的，
     * 否则补丁里的 ChatApp 拿不到 App 的类。
     */
    static final class Filter extends ClassLoader {
        private final ClassLoader app;

        Filter(ClassLoader app) {
            super(null);
            this.app = app;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name != null && name.startsWith("com.linglum.chat.ChatApp")) {
                throw new ClassNotFoundException(name);     // 逼 DexClassLoader 自己去补丁里找
            }
            return app.loadClass(name);
        }
    }
}
