package com.linglum.chat;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 端到端加密核心。
 *
 * 约定：
 *  · 身份密钥对 = EC P-256（Android 原生支持，兼容性好）
 *  · 单聊密钥 = SHA-256(ECDH(我的私钥, 对方公钥))  → AES-256-GCM
 *  · 群密钥   = 随机 32 字节，成员各自用「与群主的 ECDH 密钥」加密后存 NAS
 *  · 私钥本地保存 = 用密码派生的 KEK（PBKDF2-200k）再加密一次
 *  · 密文格式 = base64(iv) + "." + base64(ciphertext)
 */
public final class Crypto {

    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ROUNDS = 200000;

    private Crypto() {
    }

    // ---------- 密钥对 ----------

    public static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return g.generateKeyPair();
    }

    public static String pubToB64(KeyPair kp) {
        return Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);
    }

    public static String privToB64(KeyPair kp) {
        return Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP);
    }

    public static PrivateKey privFromB64(String b64) throws Exception {
        byte[] der = Base64.decode(b64, Base64.NO_WRAP);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static PublicKey pubFromB64(String b64) throws Exception {
        byte[] der = Base64.decode(b64, Base64.NO_WRAP);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    // ---------- 会话密钥 ----------

    /** ECDH → SHA-256 → AES-256 key（单聊用） */
    public static SecretKey sharedKey(PrivateKey mine, String peerPubB64) throws Exception {
        PublicKey peer = pubFromB64(peerPubB64);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(peer, true);
        byte[] secret = ka.generateSecret();
        byte[] hashed = java.security.MessageDigest.getInstance("SHA-256").digest(secret);
        return new SecretKeySpec(hashed, "AES");
    }

    /** 群密钥：随机 32 字节 */
    /** 把一段口令（二维码里的随机串）派生成 AES 密钥：SHA-256 即可（口令本身是 32 字节随机） */
    public static SecretKey keyFromSecret(String secret) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes("UTF-8"));
        return new javax.crypto.spec.SecretKeySpec(d, "AES");
    }

    public static SecretKey newGroupKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return new SecretKeySpec(k, "AES");
    }

    /** 把群密钥用「与某个成员的共享密钥」加密（群主发给成员用） */
    public static String wrapKeyForMember(SecretKey groupKey, PrivateKey myPriv, String memberPubB64)
            throws Exception {
        SecretKey pairKey = sharedKey(myPriv, memberPubB64);
        return encrypt(pairKey, Base64.encodeToString(groupKey.getEncoded(), Base64.NO_WRAP));
    }

    /** 成员解开自己那份群密钥 */
    public static SecretKey unwrapKey(PrivateKey myPriv, String ownerPubB64, String wrapped)
            throws Exception {
        SecretKey pairKey = sharedKey(myPriv, ownerPubB64);
        String b64 = decrypt(pairKey, wrapped);
        return new SecretKeySpec(Base64.decode(b64, Base64.NO_WRAP), "AES");
    }

    // ---------- AES-GCM ----------

    public static String encrypt(SecretKey key, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext.getBytes("UTF-8"));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    public static String decrypt(SecretKey key, String payload) throws Exception {
        int dot = payload.indexOf('.');
        if (dot <= 0) throw new IllegalArgumentException("密文格式不对");
        byte[] iv = Base64.decode(payload.substring(0, dot), Base64.NO_WRAP);
        byte[] ct = Base64.decode(payload.substring(dot + 1), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(c.doFinal(ct), "UTF-8");
    }

    // ---------- 文件（二进制）加解密 ----------

    public static byte[] encryptBytes(SecretKey key, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    public static byte[] decryptBytes(SecretKey key, byte[] data) throws Exception {
        byte[] iv = new byte[12];
        System.arraycopy(data, 0, iv, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = new byte[data.length - 12];
        System.arraycopy(data, 12, ct, 0, ct.length);
        return c.doFinal(ct);
    }

    // ---------- 私钥的密码保护 ----------

    public static SecretKey deriveKek(String password, String saltHex) throws Exception {
        byte[] salt = hexToBytes(saltHex);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = f.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(key, "AES");
    }

    /** 恢复码当作口令处理：用它派生 KEK 加密私钥 */
    public static String encryptPrivkeyWithCode(KeyPair kp, String recoveryCode, String saltHex)
            throws Exception {
        return encrypt(deriveKek(normalizeCode(recoveryCode), saltHex), privToB64(kp));
    }

    public static PrivateKey decryptPrivkeyWithCode(String enc, String recoveryCode, String saltHex)
            throws Exception {
        return privFromB64(decrypt(deriveKek(normalizeCode(recoveryCode), saltHex), enc));
    }

    /** 恢复码归一化：去横线、转大写 */
    public static String normalizeCode(String code) {
        if (code == null) return "";
        return code.replace("-", "").replace(" ", "").toUpperCase();
    }

    /** 生成 128 位恢复码（32 个十六进制字符，4 位一组） */
    public static String newRecoveryCode() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0 && i % 2 == 0) sb.append('-');
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    public static String newSaltHex() {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        return bytesToHex(s);
    }

    public static String encryptPrivkey(KeyPair kp, String password, String saltHex) throws Exception {
        return encrypt(deriveKek(password, saltHex), privToB64(kp));
    }

    public static PrivateKey decryptPrivkey(String enc, String password, String saltHex) throws Exception {
        String b64 = decrypt(deriveKek(password, saltHex), enc);
        return privFromB64(b64);
    }

    // ---------- 防篡改：数字签名 + 消息链 ----------

    /** 发送方用自己私钥签名（ECDSA P-256 / SHA-256） */
    public static String sign(PrivateKey priv, String data) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
        s.initSign(priv);
        s.update(data.getBytes("UTF-8"));
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP);
    }

    /** 接收方用发送方公钥验签；任何一位被改都会失败 */
    public static boolean verify(String pubB64, String data, String sigB64) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
            s.initVerify(pubFromB64(pubB64));
            s.update(data.getBytes("UTF-8"));
            return s.verify(Base64.decode(sigB64, Base64.NO_WRAP));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 宽松验签：兼容历史版本的规范串格式（老客户端可能用别的拼接方式）。
     * 每一种变体都绑定「发件人 + 收件 + 时间 + 正文」，所以仍然防篡改；
     * 只是不再要求客户端版本完全一致。
     */
    public static boolean verifyAny(String pubB64, String sigB64,
                                   String from, String toType, String toId,
                                   long ts, int seq, String prev, String text) {
        String[] variants = new String[]{
                canonical(from, toType, toId, ts, seq, prev, text),          // 现行格式
                from + "\n" + toType + "\n" + toId + "\n" + ts + "\n" + seq + "\n" + prev + "\n" + text,
                from + "\n" + toType + "\n" + toId + "\n" + ts + "\n" + sha256Hex(text),
                from + "\n" + toType + "\n" + toId + "\n" + ts + "\n" + text,
        };
        for (String v : variants) {
            if (verify(pubB64, v, sigB64)) return true;
        }
        return false;
    }

    // ---------- 签名方案 v2（V0.0.7 起）----------
    // 老方案只签正文哈希，附件 {fid,name,size,kind,mime} 和信封里的收件信息是"旁路"，
    // 服务端能偷偷换掉。v2 把它们一起签进去，并且用 sv=2 明确标出来：
    //   · sv>=2 → 只认严格格式（没有第二种松口）
    //   · sv<2  → 只用于 V0.0.6 及以前签的历史消息（兼容读取，新消息永远不这么签）

    public static final int SIG_V2 = 2;

    /** 长度前缀编码：拼串不会有歧义（不用分隔符，避免字段里带分隔符时被拼串篡改） */
    private static String fld(String s) {
        String v = s == null ? "" : s;
        return v.length() + ":" + v;
    }

    /** 附件参与签名的规范串（字段顺序固定，与 JSON 键顺序无关） */
    public static String attachSig(org.json.JSONObject a) {
        if (a == null) return "";
        return fld(a.optString("fid", "")) + fld(a.optString("name", ""))
                + fld(String.valueOf(a.optLong("size", 0))) + fld(a.optString("kind", ""))
                + fld(a.optString("mime", ""));
    }

    public static String attachSig(String attachJson) {
        if (attachJson == null || attachJson.isEmpty()) return "";
        try {
            return attachSig(new org.json.JSONObject(attachJson));
        } catch (Exception e) {
            return attachJson;          // 解析不了就按原串签，至少不会被悄悄换掉
        }
    }

    /** v2 规范串：发件人/收件/时间/序号/前一条哈希/正文哈希/附件 全部绑死 */
    public static String canonicalV2(String from, String toType, String toId,
                                     long ts, int seq, String prev, String text,
                                     String attachSigStr) {
        return "v2\n" + from + "\n" + toType + "\n" + toId + "\n" + ts + "\n" + seq + "\n"
                + prev + "\n" + sha256Hex(text) + "\n"
                + sha256Hex(attachSigStr == null ? "" : attachSigStr);
    }

    /** 统一验签入口：sv>=2 走严格格式，sv<2 走老格式（仅历史消息） */
    public static boolean verifyMessage(String pubB64, String sigB64,
                                        String from, String toType, String toId,
                                        long ts, int seq, String prev, String text,
                                        String attachJson, int sv) {
        if (pubB64 == null || sigB64 == null || sigB64.isEmpty()) return false;
        if (sv >= SIG_V2) {
            return verify(pubB64, canonicalV2(from, toType, toId, ts, seq, prev, text,
                    attachSig(attachJson)), sigB64);
        }
        return verifyAny(pubB64, sigB64, from, toType, toId, ts, seq, prev, text);
    }

    /** 被签名的规范串：发件人/收件/时间/序号/前一条哈希/内容哈希 全部绑死（仅历史消息用） */
    public static String canonical(String from, String toType, String toId,
                                   long ts, int seq, String prev, String text) {
        return from + "\n" + toType + "\n" + toId + "\n" + ts + "\n"
                + seq + "\n" + prev + "\n" + sha256Hex(text);
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 小工具 ----------

    public static String fingerprint(String pubkeyB64) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Base64.decode(pubkeyB64, Base64.NO_WRAP));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) sb.append('-');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "??";
        }
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
