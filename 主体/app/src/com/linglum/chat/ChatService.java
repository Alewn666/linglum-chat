package com.linglum.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.PrivateKey;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * 前台服务：App 退到后台/锁屏后继续收消息并弹通知。
 *
 * 设计要点（2026-10-31 修复）：
 *  · 每轮都从 prefs 重读会话（token/wxid/私钥）→ 退出换号后能立刻用新身份
 *  · 服务端返回 401（token 失效/已退出）→ 自己 stopSelf，绝不空转
 *  · 弹通知前做签名校验（和前台一致），被篡改的消息会标警告
 */
public class ChatService extends Service {

    private static final String PREFS = "linglong_chat";
    private static final String CH_SERVICE = "chat_service";
    private static final String CH_MSG = "chat_msg";

    private volatile boolean running = true;
    private SharedPreferences prefs;

    private String baseUrl = "", token = "", wxid = "";
    private PrivateKey myPriv;
    private final Map<String, String> friendPub = new HashMap<String, String>();
    private final Map<String, SecretKey> groupKeys = new HashMap<String, SecretKey>();
    private long cursor = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannels();
        startForeground(1, buildServiceNotification());
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        if (!reloadSession()) {      // 已退出登录 → 自杀
                            running = false;
                            stopSelf();
                            break;
                        }
                        pollOnce();
                    } catch (Exception e) {
                        log("轮询异常: " + e);
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 通知 ----------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel s = new NotificationChannel(
                    CH_SERVICE, "后台接收消息", NotificationManager.IMPORTANCE_MIN);
            s.setDescription("保持后台接收消息（静默）");
            s.setShowBadge(false);
            s.setSound(null, null);
            s.enableVibration(false);
            s.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(s);

            NotificationChannel m = new NotificationChannel(
                    CH_MSG, "新消息提醒", NotificationManager.IMPORTANCE_HIGH);
            m.setDescription("收到新消息时提醒");
            nm.createNotificationChannel(m);
        }
    }

    private Notification buildServiceNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_SERVICE)
                : new Notification.Builder(this);
        b.setContentTitle("正在接收消息")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .setSound(null)
                .setVibrate(null)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 31) {
            // 让系统尽量延后/低调显示这个常驻通知
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED);
        }
        return b.build();
    }

    private void notifyMessage(String from, String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_MSG)
                : new Notification.Builder(this);
        b.setContentTitle(from)
                .setContentText(text.length() > 60 ? text.substring(0, 60) + "…" : text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setAutoCancel(true)
                .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    // ---------------- 状态 ----------------

    /** 每轮重读会话；未登录返回 false */
    private boolean reloadSession() {
        String t = prefs.getString("token", "");
        String w = prefs.getString("wxid", "");
        String privB64 = prefs.getString("privkey", "");
        if (t.isEmpty() || w.isEmpty() || privB64.isEmpty()) return false;

        baseUrl = prefs.getString("base", "");
        token = t;
        wxid = w;
        if (myPriv == null || !w.equals(lastWxid)) {
            try {
                myPriv = Crypto.privFromB64(privB64);
            } catch (Exception e) {
                myPriv = null;
                return false;
            }
            lastWxid = w;
            // 只读前台的游标作为起点，之后写自己独立的键，避免和界面互相踩
            cursor = Math.max(prefs.getLong("cursor", 0),
                    prefs.getLong("svc_cursor_" + w, 0));
        }
        refreshPeers();
        return true;
    }

    private String lastWxid = "";

    private void refreshPeers() {
        friendPub.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                friendPub.put(o.getString("wxid"), o.getString("pubkey"));
            }
        } catch (Exception ignored) {
        }
        groupKeys.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("groups", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String k = o.optString("key", "");
                if (!k.isEmpty()) {
                    groupKeys.put(o.getString("gid"), new javax.crypto.spec.SecretKeySpec(
                            android.util.Base64.decode(k, android.util.Base64.NO_WRAP), "AES"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private long lastKeySync = 0;

    /** 每 60 秒对齐一次好友公钥（对方换钥后，后台通知也能正确解密） */
    private void syncFriendKeys(Api api) {
        long now = System.currentTimeMillis();
        if (now - lastKeySync < 60000L || friendPub.isEmpty()) return;
        lastKeySync = now;
        try {
            StringBuilder sb = new StringBuilder();
            for (String w : friendPub.keySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(w);
            }
            JSONObject r = api.get("/api/pubkeys?ids=" + sb);
            JSONObject us = r.optJSONObject("users");
            if (us == null) return;
            for (String w : new java.util.ArrayList<String>(friendPub.keySet())) {
                JSONObject u = us.optJSONObject(w);
                if (u == null) continue;
                String pk = u.optString("pubkey", "");
                if (!pk.isEmpty() && !pk.equals(friendPub.get(w))) {
                    friendPub.put(w, pk);
                    persistPeerPub(w, pk);
                    log("好友 " + w + " 换过密钥，已更新");
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void pollOnce() {
        Api api = new Api(baseUrl, token);
        syncFriendKeys(api);
        JSONObject r;
        try {
            r = api.getLong("/api/messages?since=" + cursor + "&wait=25");
        } catch (Exception e) {
            return;
        }
        int code = r.optInt("_http", 0);
        if (code == 401 || code == 403) {     // token 失效（退出登录/被吊销）→ 停服务
            running = false;
            stopSelf();
            return;
        }
        if (code >= 400) return;              // 426 等：不刷、不空转

        JSONArray arr = r.optJSONArray("messages");
        if (arr == null || arr.length() == 0) return;

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                long id = o.optLong("id");
                String from = o.optString("from");
                String toType = o.optString("to_type");
                String toId = o.optString("to_id");
                String payload = o.optString("payload");
                String sig = o.optString("sig");
                cursor = Math.max(cursor, id);
                prefs.edit().putLong("svc_cursor_" + wxid, cursor).apply();  // 自己的游标，不碰前台

                if (from.equals(wxid)) continue;   // 自己发的不提醒
                if ("group".equals(toType)) continue;   // 群聊功能已下线，群消息不再提醒

                SecretKey k = null;
                String peerPub = null;
                if ("group".equals(toType)) {
                    k = groupKeys.get(toId);
                    peerPub = friendPub.get(from);
                } else {
                    peerPub = friendPub.get(from);
                    if (peerPub != null) k = Crypto.sharedKey(myPriv, peerPub);
                }
                if (k == null) {
                    notifyMessage(from, "（有新消息）");
                    continue;
                }

                String raw;
                try {
                    raw = Crypto.decrypt(k, payload);
                } catch (Exception e0) {
                    // 对方找回账号换过密钥 → 拉一次最新公钥再解（跟微信一样，好友就能聊）
                    String fresh = fetchPeerPub(api, from);
                    if (fresh != null && !fresh.equals(peerPub)) {
                        friendPub.put(from, fresh);
                        persistPeerPub(from, fresh);
                        peerPub = fresh;
                        if ("group".equals(toType)) k = groupKeys.get(toId);
                        else k = Crypto.sharedKey(myPriv, fresh);
                        raw = Crypto.decrypt(k, payload);
                    } else {
                        throw e0;
                    }
                }
                String text;
                boolean tampered = false;
                if (raw.startsWith("{")) {
                    JSONObject env = new JSONObject(raw);
                    text = env.optString("t");
                    String envFrom = env.optString("f");
                    if (!envFrom.equals(from)) {
                        tampered = true;
                    } else if (peerPub != null && !sig.isEmpty()) {
                        // sv>=2：只认信封里的 v2 签名 s2（缺了就算被篡改，不降级）
                        int sv = env.optInt("sv", 1);
                        String sigUse = sig;
                        if (sv >= Crypto.SIG_V2) {
                            sigUse = env.optString("s2", "");
                            if (sigUse.isEmpty()) {
                                tampered = true;
                            }
                        }
                        if (!tampered && !Crypto.verifyMessage(peerPub, sigUse, envFrom, toType,
                                toId, env.optLong("ts"), env.optInt("s"), env.optString("p"),
                                text, env.optString("a", ""), sv)) {
                            tampered = true;
                        }
                    }
                } else {
                    text = raw;   // 旧版本消息
                }
                if (tampered) text = "⚠️ " + text + "\n（签名校验失败，消息可能被篡改）";
                notifyMessage(nameOf(from), text);
            } catch (Exception ignored) {
            }
        }
    }

    /** 取某个人的最新公钥（服务端 /api/pubkeys） */
    private String fetchPeerPub(Api api, String peer) {
        try {
            JSONObject r = api.get("/api/pubkeys?ids=" + peer);
            JSONObject us = r.optJSONObject("users");
            JSONObject u = us == null ? null : us.optJSONObject(peer);
            return u == null ? null : u.optString("pubkey", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把最新公钥写回 prefs 的好友表（前台下次启动直接可用） */
    private void persistPeerPub(String peer, String pub) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (peer.equals(o.optString("wxid")) && !pub.equals(o.optString("pubkey"))) {
                    JSONArray op = o.optJSONArray("oldpubs");
                    if (op == null) op = new JSONArray();
                    String cur = o.optString("pubkey", "");
                    if (!cur.isEmpty()) op.put(cur);
                    o.put("oldpubs", op);
                    o.put("pubkey", pub);
                    changed = true;
                }
            }
            if (changed) prefs.edit().putString("friends", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 统一日志：Logcat 里用 tag=LinglumChat 查 */
    private void log(String msg) {
        android.util.Log.w("LinglumChat", msg);
    }

    private String nameOf(String id) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (id.equals(o.getString("wxid"))) return o.optString("nickname", id);
            }
        } catch (Exception ignored) {
        }
        return id;
    }
}
