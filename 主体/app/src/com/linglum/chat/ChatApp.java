package com.linglum.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;

/**
 * 灵珑聊天 v1.0
 * 登录（专属号+密码）→ 会话列表（单聊/群聊）→ 聊天页
 * 消息端到端加密，NAS 只存密文；App 每 3 秒轮询新消息。
 */
public class ChatApp implements AppCore {

    /** 构建标记：每次发版/发补丁改这里，设置页「热更新」里能看到当前跑的是哪份代码 */
    public static final String BUILD_TAG = "V0.2.1 · 热更新1";

    /** 壳传进来的 Activity：App 里所有 Context 调用都走它（本类自己不再继承 Activity） */
    public final Activity act;

    public ChatApp(Activity a) {
        this.act = a;
    }


    private static final String PREFS = "linglong_chat";
    /** 默认服务端：内网穿透入口（指向我们自己的 NAS）。
     *  旧版默认是局域网地址 http://192.168.1.100:8097，升级时自动迁移过去。 */
    private static final String DEFAULT_BASE = "http://103.40.14.14:10100";
    private static final String LEGACY_BASE = "http://192.168.1.100:8097";
    private static final int REQ_PICK_IMG = 301;
    private static final int REQ_PICK_FILE = 302;
    private static final int REQ_CAMERA = 303;
    private static final int REQ_AVATAR = 304;
    private static final int REQ_SCAN = 305;
    private android.net.Uri pendingCamUri;

    // 轮询代次：每次（重新）登录 +1，上一代线程作废。
    // ⚠️ 2026-11-01 修：原来 startPolling() 有 isAlive 守卫，退出登录时老线程还卡在
    // 25 秒长轮询里 → 重新登录时被判定"还活着"直接 return，polling 又没置回 true，
    // 老线程醒来退出后就没有任何轮询了 → 收不到消息（只有 ChatService 的通知）。
    private volatile int pollGen = 0;

    // ---- 恢复聊天记录（重新登录后全量同步）----
    // 大 backlog 恢复期间：只更新一行进度、不整页重建（否则列表反复闪烁/跳动），
    // 每条消息的 ECDH 也缓存起来（否则 500 条 = 500 次椭圆曲线运算，慢得离谱）。
    private volatile boolean restoringHistory = false;
    private volatile int restoreCount = 0;
    private TextView restoreTip;
    private final java.util.Map<String, javax.crypto.SecretKey> peerKeyCache =
            new java.util.HashMap<String, javax.crypto.SecretKey>();

    // ---- 朋友圈 ----
    private static final int REQ_MOMENT_IMG = 306;
    private final java.util.List<JSONObject> moments = new java.util.ArrayList<JSONObject>();
    private final java.util.Map<String, javax.crypto.SecretKey> momentKeyCache =
            new java.util.HashMap<String, javax.crypto.SecretKey>();
    private final java.util.Map<String, android.graphics.Bitmap> momentImgCache =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, android.graphics.Bitmap> eldest) {
                    return size() > 60;   // 最多缓存 60 张，防内存爆
                }
            };
    private final java.util.concurrent.ExecutorService imgPool =
            java.util.concurrent.Executors.newFixedThreadPool(4);
    private final java.util.List<android.net.Uri> composePics = new java.util.ArrayList<android.net.Uri>();
    private LinearLayout momentFeed;
    private ScrollView momentsScroll;
    private LinearLayout composePicBox;
    private EditText composeText;
    private boolean hasMoreMoments = false;
    private String momentErr = null;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String baseUrl, token, wxid, nickname, pubkeyB64;
    private PrivateKey myPriv;
    private Api api;

    private LinearLayout rootCol;
    private boolean dark;
    private int accent, accentSoft;

    private final List<Friend> friends = new ArrayList<Friend>();
    private final List<Group> groups = new ArrayList<Group>();
    private final List<Msg> messages = new java.util.concurrent.CopyOnWriteArrayList<Msg>();
    private long cursor = 0;

    private String openChat;                 // "u:wxid" / "g:gid"，null = 列表页
    private LinearLayout chatList;           // 消息气泡容器
    private ScrollView chatScroll;
    // 刚打开会话 → 第一帧就要在底部（否则会"先闪一下顶部再跳到底"）
    private boolean chatFirstRender = false;
    // 已渲染状态：用来判断"能不能只在末尾追加"，避免每条新消息都整表重建
    private String renderedConv = null;
    private int renderedCount = 0;
    private String renderedSig = "";
    private long renderedLastTs = 0;
    private TextView chatTitle;
    // 头像缓存：以前每渲染一行消息都重新读文件+解码+缩放+切圆角（一屏几十条 = 几十次磁盘 IO），
    // 打开会话明显卡顿。按 wxid 缓存最终那张小圆角图，missing 记住"确实没有头像"的，避免反复 stat。
    private final java.util.HashMap<String, android.graphics.Bitmap> avatarCache =
            new java.util.HashMap<String, android.graphics.Bitmap>();
    private final java.util.HashSet<String> avatarMissing = new java.util.HashSet<String>();

    private boolean polling = true;
    private Thread pollThread;
    private boolean updateDialogShown = false;
    private boolean sessionEnded = false;
    private boolean tablet = false;   // 平板双栏   // 已经因会话失效退出过，避免重复处理     // 更新框只弹一次，别刷屏
    private volatile boolean upgradeRequired = false;  // 被服务端判定版本过低 → 暂停请求
    /** 当前底部页签：0 微信 / 1 通讯录 / 2 发现 / 3 我 */
    private int currentTab = 0;

    /** 状态栏高度（状态栏透明后，用它把内容让下来） */
    private int statusBarH = 0;

    /** 键盘（输入法）高度：有的机型 adjustResize 不生效（如小米），只能自己把输入栏顶到键盘上方 */
    private int imePad = 0;
    private int imeInset = 0;      // 系统上报的 IME inset（API 20+）
    private int rootMaxH = 0;      // 没被键盘压缩时根视图的最大高度，用来判断系统是否已经替我们缩小了窗口

    /** 平板左栏容器 + 左侧页签文字（用于「只换左栏、不动聊天区」） */
    private LinearLayout wideLeftHost;
    private final java.util.List<TextView[]> railItemViews = new java.util.ArrayList<TextView[]>();

    /** 平板两栏时：左栏容器 / 左侧页签栏（切页签只重填这两块，右侧聊天区保持不动） */
    private LinearLayout wideLeft;
    private LinearLayout wideRail;

    /** 上次同步好友公钥的时间（毫秒） */
    private long lastKeySync = 0;

    /** 对方公钥缓存（wxid -> pubkey）：没加好友也能解密对方发来的消息 */
    private final java.util.HashMap<String, String> peerPubCache = new java.util.HashMap<String, String>();
    /** 同步状态（界面上可见，方便排查） */
    private String syncInfo = "尚未同步";
    private TextView syncView;
    private final StringBuilder syncLog = new StringBuilder();
    private boolean updateChecked = false;

    // ---------------- 数据模型 ----------------

    static class Friend {
        String wxid, nickname, pubkey;
        int ver = 1;                    // 公钥版本号（服务端），换过密钥就变大
        int avatarVer = 0;              // 头像版本号（服务端），换过头像就变大
        List<String> oldPubs = new ArrayList<String>();  // 历史公钥：用来解密换钥前的旧消息
    }

    static class Group {
        String gid, name, owner, myWrapped;
        String wrappedBy = "";          // 谁替我包裹的群密钥（换钥后不是群主了）
        List<String> members = new ArrayList<String>();
        List<String> admins = new ArrayList<String>();   // 群管理员（群主可设，最多 10 人）
        int nameAdminOnly = 0;                           // 1 = 仅群主/管理员可改群名
        int allowMemberInvite = 1;                       // 1 = 普通成员也能邀请人进群
        String notice = "";                              // 群公告（群主/管理员可改）
        java.util.Map<String, String> memberNames = new java.util.HashMap<String, String>(); // 非好友成员的昵称
        SecretKey key;      // 运行时解出的群密钥
    }

    static class Msg {
        long id;
        String from, toType, toId, payload, plain;
        String sig;          // 发送方签名
        int seq;             // 消息链序号
        String prev;         // 前一条消息的哈希
        int verified;        // 1=校验通过 / -1=校验失败 / 0=未校验（旧消息）
        String attach;       // 附件信息 JSON：{fid,name,size,kind,mime}
        boolean recalled;    // 已撤回
        long ts;
    }

    // ---------------- 生命周期 ----------------

    public void create(Bundle savedInstanceState) {
        prefs = act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        tablet = act.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        Api.APP_VERSION = myVersionCode();
        detectPalette();
        // 状态栏"补齐"（真·补齐）：顶栏背景一直画到屏幕最顶（状态栏后面），
        // 顶栏内容再用 statusBarH 让下来 —— 状态栏不再是自带的一条异色白带，
        // 而是顶栏本身（左白右灰）的延续。只铺顶部，底部导航栏/键盘行为不动。
        statusBarH = statusBarHeight();
        if (Build.VERSION.SDK_INT >= 21) {
            act.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            int topColor = dark ? 0xFF0A0C11 : 0xFFEEF2F8;
            int bottomColor = dark ? 0xFF161B24 : 0xFFFCFDFF;
            act.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);   // 让界面画进来
            act.getWindow().setNavigationBarColor(bottomColor);
            act.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(topColor));
            if (Build.VERSION.SDK_INT >= 28) {   // 挖孔/刘海区也让界面铺进去
                act.getWindow().getAttributes().layoutInDisplayCutoutMode =
                        Build.VERSION.SDK_INT >= 30
                                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26 && !dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            act.getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        requestNotificationPermission();
        baseUrl = prefs.getString("base", DEFAULT_BASE);
        if (baseUrl.trim().isEmpty() || baseUrl.equals(LEGACY_BASE)) {
            baseUrl = DEFAULT_BASE;                 // 老用户自动切到穿透地址，不用手动改
            prefs.edit().putString("base", baseUrl).apply();
        }
        token = prefs.getString("token", "");
        wxid = prefs.getString("wxid", "");
        nickname = prefs.getString("nickname", "");
        pubkeyB64 = prefs.getString("pubkey", "");
        String privB64 = prefs.getString("privkey", "");

        if (!privB64.isEmpty()) {
            try {
                myPriv = Crypto.privFromB64(privB64);
            } catch (Exception ignored) {
            }
        }
        loadLocalState();

        if (!token.isEmpty() && myPriv != null) {
            api = new Api(baseUrl, token);
            showHome();
            startPolling();
            startChatService();
            refreshFriendProfiles();    // 通讯录（昵称/头像/公钥）以服务端为准
            refreshAllFriendKeys();     // 冷启动先把好友公钥对齐（找回账号换过密钥也能对上）
            refreshGroups();
        } else {
            showLogin();
        }
    }

    public void resume() {
        // 兜底：从后台回来时如果轮询线程已经没了，重新拉起来（正常情况是个空判断）
        if (api != null && (pollThread == null || !pollThread.isAlive())) {
            startPolling();
        }
    }

    public void newIntent(Intent intent) {
        // 点通知/桌面图标再次进来：不叠新的 Activity，原地刷新
        if (token == null || token.isEmpty()) return;
        try {
            if (openChat == null) showTab(currentTab);
            else renderMessages();
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        polling = false;
        pollGen++;
    }

    // ---------------- 视觉（沿用液态玻璃） ----------------

    private void detectPalette() {
        dark = (act.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // 自己的主色：青绿（比微信绿更冷一点），深浅色统一
        accent = dark ? 0xFF2FD6A6 : 0xFF10A37F;
        accentSoft = dark ? 0xFF123A31 : 0xFFE4F6F0;
    }

    /** 页面底色：偏冷的浅灰（不是微信那种暖灰） */
    private GradientDrawable background() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? 0xFF0F1115 : 0xFFF5F6F8);
        return g;
    }

    /** 液态玻璃面板：半透明填充 + 高光细边 + 大圆角 */
    private GradientDrawable glassCard(int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(dark ? 0x1FFFFFFF : 0xE8FFFFFF);
        g.setStroke(1, dark ? 0x33FFFFFF : 0x9AFFFFFF);
        return g;
    }

    /** 玻璃条（顶栏 / 底栏 / 输入栏）：薄一点、亮一点 */
    private GradientDrawable glassBar() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? 0x14FFFFFF : 0xD9FFFFFF);
        g.setStroke(1, dark ? 0x22FFFFFF : 0x80FFFFFF);
        return g;
    }

    private int cardBg() {
        return dark ? 0xFF171A1F : 0xFFFFFFFF;
    }

    private int textMain() {
        return dark ? 0xFFECEEF2 : 0xFF14181F;
    }

    private int textSub() {
        return 0xFF8A93A3;
    }

    private int divider() {
        return dark ? 0xFF232A33 : 0xFFE8ECF1;
    }

    private int statusBarHeight() {
        try {
            int id2 = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id2 > 0) return act.getResources().getDimensionPixelSize(id2);
        } catch (Exception ignored) {
        }
        return dp(24);
    }

    /** 系统原生的水波纹点击反馈 */
    private android.graphics.drawable.Drawable rippleBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            try {
                return act.getDrawable(tv.resourceId);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 圆角卡片背景 */
    private GradientDrawable cardBgDrawable(int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(cardBg());
        return g;
    }

    private GradientDrawable glass(int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(dark ? 0x22FFFFFF : 0xE6FFFFFF);
        g.setStroke(dp(1), dark ? 0x30FFFFFF : 0x55FFFFFF);
        return g;
    }

    private GradientDrawable bubble(boolean mine) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(mine ? accent : (dark ? 0x33FFFFFF : 0xFFFFFFFF));
        return g;
    }

    private static int blend(int base, int other, float ratio) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or_ = (other >> 16) & 0xFF, og = (other >> 8) & 0xFF, ob = other & 0xFF;
        return 0xFF000000
                | ((int) (br + (or_ - br) * ratio) << 16)
                | ((int) (bg + (og - bg) * ratio) << 8)
                | (int) (bb + (ob - bb) * ratio);
    }

    private void reset() {
        wideLeftHost = null;
        restoreTip = null;
        groupListPage = false;
        newFriendsPage = false;
        // 返回键的页面登记统一在这里清掉；每个 show 方法在 reset() 之后再登记自己
        loginPage = false;
        momentsPage = false;
        composePage = false;
        chatPage = false;
        pickBack = null;
        railItemViews.clear();
        rootCol = new LinearLayout(act);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackground(background());
        rootCol.setPadding(0, 0, 0, 0);   // 顶部条自己把背景画进状态栏、内容让位（topBar/buildChatInto）
        act.setContentView(rootCol);
        applyPageAnim();
        installImeHandling();
    }

    // ---------------- 页面切换动画 ----------------
    // 全应用只有一个 Activity，所有页面都靠 reset() 换根视图 —— 动画只能做"入场"这一半
    // （旧视图在 setContentView 那一刻就没了，做不了出场动画）。四种：
    //   FWD   前进（打开聊天/子页）→ 从右滑入 + 淡入
    //   BACK  后退（返回键 / 页内 ‹）→ 从左滑入 + 淡入
    //   TAB   底部页签切换 → 纯淡入 150ms（微信式，很轻）
    //   FADE  登录页 → 纯淡入
    // ⚠️ 只有手势/返回键会排队动画；轮询/刷新重绘同一页（page() 判 pageId 没变）绝不动画，
    //    否则每刷一次页面就闪一下。
    private static final int ANIM_NONE = 0, ANIM_FWD = 1, ANIM_BACK = 2, ANIM_TAB = 3, ANIM_FADE = 4;
    private int pendingAnim = ANIM_NONE;
    private String pageId = "";

    /**
     * 每个 show 方法在 reset() 之前调：登记页面身份；只有"换页了"才排入场动画。
     * 调用方已显式排好的动画（返回键/‹ 按钮）优先，不被覆盖。
     */
    private String page(String id, int anim) {
        if (id.equals(pageId)) {
            pendingAnim = ANIM_NONE;      // 同一页重绘（刷新/轮询）→ 不动画
        } else if (pendingAnim == ANIM_NONE) {
            pendingAnim = anim;
        }
        pageId = id;
        return id;
    }

    /** reset() 里 setContentView 之后调：把排队的入场动画放到新根视图上 */
    private void applyPageAnim() {
        int a = pendingAnim;
        pendingAnim = ANIM_NONE;
        if (a == ANIM_NONE || rootCol == null) return;
        final View v = rootCol;
        float dx = 0f;
        int dur;
        if (a == ANIM_FWD || a == ANIM_BACK) {
            int w = v.getResources().getDisplayMetrics().widthPixels;
            dx = (a == ANIM_FWD ? 1f : -1f) * Math.min(w, 1400);
            dur = 260;
        } else if (a == ANIM_TAB) {
            dur = 150;
        } else {
            dur = 200;
        }
        final int fdur = dur;
        v.setAlpha(0f);                   // 先藏起来；post 到 attach 后再启动动画，不闪白
        if (dx != 0f) v.setTranslationX(dx);
        v.post(new Runnable() {
            @Override
            public void run() {
                v.animate().alpha(1f).translationX(0f).setDuration(fdur)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .start();
            }
        });
        // 兜底：动画万一被打断/没跑完，也必须把视图钉回最终可见态 ——
        // 可见性绝不依赖动画回调（V0.2.4 永久白屏的教训）
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                v.setAlpha(1f);
                v.setTranslationX(0f);
            }
        }, fdur + 200);
    }

    // ---------------- 键盘（输入法）适配 ----------------

    /**
     * 让输入栏"顶到键盘上方"，并把最新消息顶到可见区域（和微信一致）。
     *
     * 为什么不只靠 manifest 里的 adjustResize：这台小米（HyperOS）在
     * STATUS_UI_FLAG_LAYOUT_FULLSCREEN 下不会缩窗口，键盘直接盖住输入栏（用户实测截图）。
     * 所以自己算键盘高度，给根视图加底部 padding —— 输入栏自然被顶上去，聊天区也跟着变矮。
     * 两种机型的兼容：如果系统确实缩了窗口（adjustResize 生效），rootH 会变小，
     * 用 shrink 把这部分扣掉，绝不重复垫高。
     */
    private void installImeHandling() {
        final LinearLayout root = rootCol;
        if (root == null) return;
        if (Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    int ime = 0;
                    if (Build.VERSION.SDK_INT >= 30) {
                        ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                    } else {
                        ime = insets.getSystemWindowInsetBottom();
                    }
                    // 软键盘收起后这里可能是导航栏高度，交给 applyImePad 里的阈值过滤
                    imeInset = Math.max(0, ime);
                    applyImePad();
                    return insets;
                }
            });
            root.requestApplyInsets();
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (root != rootCol) return;      // 页面已换，这个监听会跟着旧视图一起走
                        applyImePad();
                    }
                });
    }

    /** 按当前键盘高度给根视图补底部 padding（幂等，只在变化时动布局） */
    private void applyImePad() {
        if (rootCol == null) return;
        View rv = rootCol.getRootView();
        int rootH = rv.getHeight();
        if (rootH <= 0) return;
        android.graphics.Rect r = new android.graphics.Rect();
        rv.getWindowVisibleDisplayFrame(r);
        int covered = rootH - r.bottom;                    // 窗口没被缩小：键盘盖住的高度
        int kb = Math.max(covered, imeInset);
        if (kb <= dp(100)) kb = 0;                         // 太矮的多半是导航条，不是键盘
        if (kb == 0) rootMaxH = Math.max(rootMaxH, rootH); // 记下"没键盘时"的窗口高度
        int shrink = rootMaxH > 0 ? Math.max(0, rootMaxH - rootH) : 0;   // 系统已经替我们缩掉的高度
        int pad = Math.max(0, kb - shrink);
        if (pad != rootCol.getPaddingBottom()) {
            rootCol.setPadding(rootCol.getPaddingLeft(), rootCol.getPaddingTop(),
                    rootCol.getPaddingRight(), pad);
            imePad = pad;
            if (pad > 0) pinChatToBottom(3);               // 键盘弹起：把最新消息顶到输入栏上方
        }
    }

    // ---------------- 登录页 ----------------

    private void showLogin() {
        openChat = null;
        pageId = page("login", ANIM_FADE);
        reset();
        loginPage = true;   // 返回键=退出应用（登录页没有上一页）

        TextView title = new TextView(act);
        title.setText("灵珑聊天");
        title.setTextSize(26);
        title.setTextColor(dark ? 0xFFF2F5FA : 0xFF16203A);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(36), 0, dp(8));
        rootCol.addView(title);

        TextView sub = new TextView(act);
        sub.setText("端到端加密 · 只走自家的 NAS");
        sub.setTextSize(13);
        sub.setTextColor(dark ? 0xFFB9C2D0 : 0xFF7C8698);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(30));
        rootCol.addView(sub);

        final EditText etId = input("玲珑号（3-32 位字母/数字/下划线）", prefs.getString("last_wxid", ""));
        final EditText etPw = input("密码", "");
        etPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText etPw2 = input("确认密码（再输一遍）", "");
        etPw2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText etNick = input("昵称（注册时填）", "");
        final EditText etPhone = input("手机号（注册时必填一个）", "");
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        final EditText etEmail = input("邮箱（注册时必填一个）", "");
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        rootCol.addView(etId);
        rootCol.addView(etPw);
        rootCol.addView(etPw2);
        rootCol.addView(etNick);
        rootCol.addView(etPhone);
        rootCol.addView(etEmail);

        final TextView status = new TextView(act);
        status.setTextColor(0xFFD14343);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(4));
        rootCol.addView(status);

        final boolean[] isRegister = {false};

        Button main = new Button(act);
        main.setText("登 录");
        main.setTextColor(Color.WHITE);
        main.setAllCaps(false);
        main.setTextSize(18);
        main.setBackground(buttonBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(16);
        rootCol.addView(main, lp);

        Button forgot = new Button(act);
        forgot.setText("忘记密码？");
        forgot.setAllCaps(false);
        forgot.setTextSize(13);
        forgot.setTextColor(0xFF8A94A6);
        forgot.setBackgroundColor(Color.TRANSPARENT);
        forgot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogForgotPassword();
            }
        });
        rootCol.addView(forgot);

        final Button toggle = new Button(act);
        toggle.setText("没有账号？注册一个");
        toggle.setAllCaps(false);
        toggle.setTextColor(accent);
        toggle.setBackgroundColor(Color.TRANSPARENT);
        rootCol.addView(toggle);

        main.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 服务器地址不再让用户改：固定用内置地址（登录页不显示这一栏）
                final String id = etId.getText().toString().trim();
                final String pw = etPw.getText().toString();
                final String nick = etNick.getText().toString().trim();
                final String phone = etPhone.getText().toString().trim();
                final String email = etEmail.getText().toString().trim();
                final String pw2 = etPw2.getText().toString();
                prefs.edit().putString("base", baseUrl)
                        .putString("last_wxid", id).apply();
                status.setText("");
                if (isRegister[0]) {
                    if (!pw.equals(pw2)) {
                        status.setText("两次输入的密码不一致");
                        return;
                    }
                    if (phone.isEmpty() && email.isEmpty()) {
                        status.setText("手机号或邮箱至少填一个");
                        return;
                    }
                }
                main.setEnabled(false);
                if (isRegister[0]) doRegister(id, pw, nick, phone, email, status, main);
                else doLogin(id, pw, status, main);
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isRegister[0] = !isRegister[0];
                main.setText(isRegister[0] ? "注册并登录" : "登 录");
                toggle.setText(isRegister[0] ? "已有账号？去登录" : "没有账号？注册一个");
                etNick.setVisibility(isRegister[0] ? View.VISIBLE : View.GONE);
                etPw2.setVisibility(isRegister[0] ? View.VISIBLE : View.GONE);
                etPhone.setVisibility(isRegister[0] ? View.VISIBLE : View.GONE);
                etEmail.setVisibility(isRegister[0] ? View.VISIBLE : View.GONE);
            }
        });
        etNick.setVisibility(View.GONE);
        etPw2.setVisibility(View.GONE);
        etPhone.setVisibility(View.GONE);
        etEmail.setVisibility(View.GONE);

        if (!updateChecked) {          // 未登录也能收到更新提示
            updateChecked = true;
            checkUpdate();
        }
    }

    private void doRegister(final String id, final String pw, final String nick,
                            final String phone, final String email,
                            final TextView status, final Button main) {
        if (id.length() < 3 || pw.length() < 6) {
            status.setText("玲珑号至少 3 位、密码至少 6 位");
            main.setEnabled(true);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    KeyPair kp = Crypto.genKeyPair();
                    String salt = Crypto.newSaltHex();
                    String privEnc = Crypto.encryptPrivkey(kp, pw, salt);

                    JSONObject body = new JSONObject();
                    body.put("wxid", id);
                    body.put("password", pw);
                    body.put("nickname", nick.isEmpty() ? id : nick);
                    body.put("phone", phone);
                    body.put("email", email);
                    body.put("pubkey", Crypto.pubToB64(kp));
                    body.put("privkey_enc", privEnc);
                    body.put("kdf_salt", salt);

                    JSONObject r = new Api(baseUrl, "").post("/api/register", body);
                    if (r.optInt("_http", 0) >= 400) {
                        fail(status, main, r.optString("error", "注册失败"));
                        return;
                    }
                    // 注册成功 → 直接登录
                    loginAfterRegister(id, pw, kp, status, main);
                } catch (Exception e) {
                    fail(status, main, "网络错误：" + e.getMessage());
                }
            }
        }).start();
    }

    private void loginAfterRegister(final String id, final String pw, final KeyPair kp,
                                    final TextView status, final Button main) {
        try {
            JSONObject body = new JSONObject();
            body.put("wxid", id);
            body.put("password", pw);
            JSONObject r = new Api(baseUrl, "").post("/api/login", body);
            if (r.optInt("_http", 0) >= 400) {
                fail(status, main, r.optString("error", "登录失败"));
                return;
            }
            myPriv = kp.getPrivate();
            saveSession(r.optString("token"), id, r.optString("nickname", id),
                    r.optString("pubkey"), Crypto.privToB64(kp));
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showHome();
                    startPolling();
                    startChatService();
                    refreshFriendProfiles();    // 通讯录以服务端为准（换手机后自动回来）
                    refreshAllFriendKeys();     // 换过密钥的好友在这里一次性对齐
                    refreshGroups();
                }
            });
        } catch (Exception e) {
            fail(status, main, "网络错误：" + e.getMessage());
        }
    }

    private void doLogin(final String id, final String pw, final TextView status, final Button main) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("wxid", id);
                    body.put("password", pw);
                    JSONObject r = new Api(baseUrl, "").post("/api/login", body);
                    if (r.optInt("_http", 0) == 426) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                forceUpdate();
                            }
                        });
                        fail(status, main, "客户端版本过低，请先更新");
                        return;
                    }
                    if (r.optInt("_http", 0) >= 400) {
                        fail(status, main, r.optString("error", "登录失败"));
                        return;
                    }
                    PrivateKey priv = Crypto.decryptPrivkey(
                            r.getString("privkey_enc"), pw, r.getString("kdf_salt"));
                    myPriv = priv;
                    prefs.edit().putInt("my_avatar_ver", r.optInt("avatar_ver", 0)).apply();
                    saveSession(r.optString("token"), id, r.optString("nickname", id),
                            r.optString("pubkey"), Crypto.privToB64(
                                    new java.security.KeyPair(
                                            Crypto.pubFromB64(r.optString("pubkey")), priv)));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showHome();
                            startPolling();
                            startChatService();
                            // ⚠️ 2026-11-01 修：退出登录会清空本地好友缓存，
                            // 老账号重新登录这条路径原先漏了这一步 → 好友全"消失"
                            refreshFriendProfiles();
                            refreshAllFriendKeys();
                            refreshGroups();
                        }
                    });
                } catch (Exception e) {
                    fail(status, main, "登录失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void fail(final TextView status, final Button main, final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(msg);
                main.setEnabled(true);
            }
        });
    }

    private void saveSession(String tok, String id, String nick, String pub, String priv) {
        sessionEnded = false;
        prefs.edit().putString("token", tok).putString("wxid", id)
                .putString("nickname", nick).putString("pubkey", pub)
                .putString("privkey", priv).apply();
        token = tok;
        wxid = id;
        nickname = nick;
        pubkeyB64 = pub;
        api = new Api(baseUrl, tok);
    }

    /** 退出登录：通知服务端吊销 token，然后走统一清理 */
    private void logout() {
        final Api a = api;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (a != null) a.post("/api/logout", new JSONObject());
                } catch (Exception ignored) {
                }
            }
        }).start();
        handleSessionEnd("已退出登录");
    }

    /** 会话结束（主动退出 / 账号在服务端被删除 / token 失效）：清本地并回登录页 */
    private void handleSessionEnd(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (sessionEnded) return;
                sessionEnded = true;
                polling = false;
                pollGen++;              // 作废所有在跑的轮询线程（含卡在长轮询里的）
                upgradeRequired = false;
                try {
                    act.stopService(new Intent(act, ChatService.class));
                } catch (Exception ignored) {
                }

                File f = new File(act.getFilesDir(), "messages.jsonl");
                if (f.exists()) f.delete();      // 本地聊天记录一并清除

                SharedPreferences.Editor e = prefs.edit();
                for (String k : prefs.getAll().keySet()) {
                    if (k.equals("base") || k.equals("last_wxid")) continue;   // 保留服务器地址
                    e.remove(k);
                }
                e.apply();

                token = "";
                wxid = "";
                nickname = "";
                pubkeyB64 = "";
                myPriv = null;
                api = null;
                messages.clear();
                cursor = 0;             // ⚠️ 游标必须清零：否则重登后只拉增量，历史永远不回来
                friends.clear();
                groups.clear();
                peerPubCache.clear();

                showLogin();
                toast(msg);
            }
        });
    }

    // ---------------- 恢复码 / 找回 ----------------

    /** 忘记密码：申请 128 位救援密钥 → 给好友 → 好友批准 → 设新密码 */
    private void dialogForgotPassword() {
        final EditText etId = new EditText(act);
        etId.setHint("你的玲珑号");
        final EditText etContact = new EditText(act);
        etContact.setHint("注册时填的手机号或邮箱");
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.addView(etId);
        box.addView(etContact);

        new AlertDialog.Builder(act)
                .setTitle("找回密码")
                .setMessage("第一步：申请一串 128 位救援密钥，然后发给一位好友，请他在自己的 App 里输入批准。")
                .setView(box)
                .setPositiveButton("申请密钥", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String id = etId.getText().toString().trim();
                        final String contact = etContact.getText().toString().trim();
                        if (id.isEmpty()) return;
                        if (contact.isEmpty()) {
                            toast("请填写注册时的手机号或邮箱");
                            return;
                        }
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Api a = new Api(prefs.getString("base", DEFAULT_BASE), "");
                                    JSONObject r = a.post("/api/recovery/apply",
                                            new JSONObject().put("wxid", id).put("contact", contact));
                                    if (r.optInt("_http", 0) >= 400) {
                                        final String err = r.optString("error", "申请失败");
                                        ui.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast(err);
                                            }
                                        });
                                        return;
                                    }
                                    final String key = r.getString("key128");
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dialogShowRescueKey(id, key);
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("申请失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 显示 128 位救援密钥，并等你确认「好友已批准」 */
    private void dialogShowRescueKey(final String wxid, final String key) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(14), dp(22), dp(4));
        TextView t = new TextView(act);
        t.setText("把这串密钥发给一位好友，请他在自己的 App 里「＋ → 帮朋友找回」输入。\n有效期 30 分钟。");
        t.setTextSize(13);
        t.setTextColor(dark ? 0xFFB9C2D0 : 0xFF5A6678);
        box.addView(t);

        TextView kv = new TextView(act);
        kv.setText(key);
        kv.setTextIsSelectable(true);
        kv.setTypeface(android.graphics.Typeface.MONOSPACE);
        kv.setTextSize(15);
        kv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(dark ? 0x22FFFFFF : 0xFFF1F5FB);
        kv.setBackground(bg);
        kv.setPadding(dp(10), dp(14), dp(10), dp(14));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(12);
        box.addView(kv, klp);

        new AlertDialog.Builder(act)
                .setTitle("救援密钥")
                .setView(box)
                .setPositiveButton("好友已批准，继续", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        dialogEnterNewPassword(wxid, key);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 输新密码 → 本机有旧私钥则保留历史，否则换新密钥对 */
    private void dialogEnterNewPassword(final String wxid, final String key) {
        final EditText etPw = new EditText(act);
        etPw.setHint("新密码（至少 6 位）");
        etPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(etPw);

        new AlertDialog.Builder(act)
                .setTitle("设置新密码")
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String pw = etPw.getText().toString();
                        if (pw.length() < 6) {
                            toast("密码至少 6 位");
                            return;
                        }
                        doResetPassword(wxid, key, pw);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真正重置：优先用本机旧私钥（保留历史），没有就生成新密钥对（历史无法恢复） */
    private void doResetPassword(final String wxid, final String key, final String newPw) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api a = new Api(prefs.getString("base", DEFAULT_BASE), "");
                    // 先确认好友批准了
                    JSONObject cr = a.post("/api/recovery/check",
                            new JSONObject().put("wxid", wxid).put("key128", key));
                    if (cr.optInt("_http", 0) >= 400) {
                        throw new Exception(cr.optString("error", "验证失败"));
                    }
                    if (!cr.optBoolean("approved")) {
                        throw new Exception("好友还没有批准，请他先输入这串密钥");
                    }

                    String newSalt = Crypto.newSaltHex();
                    String enc;
                    String pubB64 = null;
                    boolean keptHistory = false;
                    // 本机还存着旧私钥 → 保留历史消息
                    String localPriv = prefs.getString("privkey", "");
                    if (!localPriv.isEmpty()) {
                        try {
                            PrivateKey priv = Crypto.privFromB64(localPriv);
                            KeyPair kp = new KeyPair(Crypto.pubFromB64(pubkeyB64), priv);
                            enc = Crypto.encryptPrivkey(kp, newPw, newSalt);
                            keptHistory = true;
                        } catch (Exception e) {
                            KeyPair kp = Crypto.genKeyPair();
                            enc = Crypto.encryptPrivkey(kp, newPw, newSalt);
                            pubB64 = Crypto.pubToB64(kp);
                        }
                    } else {
                        KeyPair kp = Crypto.genKeyPair();
                        enc = Crypto.encryptPrivkey(kp, newPw, newSalt);
                        pubB64 = Crypto.pubToB64(kp);
                    }

                    JSONObject fr = new JSONObject().put("wxid", wxid)
                            .put("key128", key).put("password", newPw)
                            .put("privkey_enc", enc).put("kdf_salt", newSalt);
                    if (pubB64 != null) fr.put("pubkey", pubB64);
                    JSONObject r2 = a.post("/api/recovery/finish", fr);
                    if (r2.optInt("_http", 0) >= 400) {
                        throw new Exception(r2.optString("error", "重置失败"));
                    }
                    final boolean kept = keptHistory;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(kept ? "✅ 密码已重置，历史消息保留"
                                       : "✅ 密码已重置。历史消息无法恢复，但好友照常能聊（已重新同步密钥）");
                            showLogin();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 好友：帮朋友找回（输入对方的 128 位密钥批准） */
    private void dialogApproveFriend() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        final EditText etId = new EditText(act);
        etId.setHint("朋友的专属号");
        box.addView(etId);
        final EditText etKey = new EditText(act);
        etKey.setHint("他给你的 128 位密钥");
        etKey.setTextSize(13);
        etKey.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(etKey);

        new AlertDialog.Builder(act)
                .setTitle("帮朋友找回")
                .setMessage("朋友点「忘记密码」会拿到一串密钥，输入它即可为他批准这次找回。")
                .setView(box)
                .setPositiveButton("批准", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String id = etId.getText().toString().trim();
                        final String key = etKey.getText().toString().trim();
                        if (id.isEmpty() || key.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    JSONObject body = new JSONObject();
                                    body.put("wxid", id);
                                    body.put("key128", key);
                                    JSONObject r = api.post("/api/recovery/approve", body);
                                    final String msg = r.optInt("_http", 0) >= 400
                                            ? r.optString("error", "批准失败")
                                            : "✅ 已为 " + id + " 批准找回";
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast(msg);
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }


    // ---------------- 会话列表 ----------------

    private void showHome() {
        showTab(currentTab);
    }

    /** 四个底部页签：0 微信 / 1 通讯录 / 2 发现 / 3 我 */
    private void showTab(int tab) {
        pageId = page("tab" + tab, ANIM_TAB);
        if (twoPane()) pendingAnim = ANIM_NONE;   // 双栏：整页重绘其实只换右栏，整页滑反而晃
        currentTab = tab;
        if (!tablet) openChat = null;
        reset();
        if (twoPane()) {                    // 只有横屏平板才分栏；竖屏平板走单栏
            showWideLayout();
            return;
        }
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        rootCol.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (tab == 1) buildContactsInto(content);
        else if (tab == 2) buildDiscoverInto(content);
        else if (tab == 3) buildMeInto(content);
        else buildChatsInto(content);
        rootCol.addView(bottomBar());
    }

    /** 底部 tab 栏（像微信那样） */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(glassBar());
        View line = new View(act);
        line.setBackgroundColor(divider());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        String[] icons = {"ic_tab_chat", "ic_tab_contacts", "ic_tab_discover", "ic_tab_me"};
        String[] names = {"微信", "通讯录", "发现", "我"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            boolean on = (i == currentTab);
            int c = on ? accent : textSub();
            View ic = tabIcon(icons[i], c, 23);
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView tx = new TextView(act);
            tx.setText(names[i]);
            tx.setTextSize(10.5f);
            tx.setTextColor(c);
            tx.setGravity(Gravity.CENTER);
            item.addView(ic);
            item.addView(tx);
            if (on) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(16));
                pill.setColor(accentSoft);
                item.setBackground(pill);
            }
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx != currentTab) showTab(idx);
                }
            });
            bar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return wrap;
    }

    /** 用资源名找图标并着色（本项目没有生成 R 类，所以用 getIdentifier） */
    private View tabIcon(String name, int color, int sizeDp) {
        int id = 0;
        try {
            id = act.getResources().getIdentifier(name, "drawable", act.getPackageName());
        } catch (Exception ignored) {
        }
        if (id == 0) {
            TextView tv = new TextView(act);
            tv.setText("•");
            tv.setTextColor(color);
            tv.setGravity(Gravity.CENTER);
            return tv;
        }
        android.widget.ImageView iv = new android.widget.ImageView(act);
        iv.setImageResource(id);
        iv.setColorFilter(color);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 列表页顶部的"正在恢复聊天记录"提示（不恢复时 GONE） */
    private View restoreTipView() {
        restoreTip = new TextView(act);
        restoreTip.setTextSize(13);
        restoreTip.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        restoreTip.setPadding(dp(16), dp(10), dp(16), dp(4));
        if (restoringHistory) {
            restoreTip.setText("⏳ 正在恢复聊天记录…已恢复 " + restoreCount + " 条");
            restoreTip.setVisibility(View.VISIBLE);
        } else {
            restoreTip.setVisibility(View.GONE);
        }
        return restoreTip;
    }

    private void updateRestoreTip() {
        if (restoreTip == null) return;
        restoreTip.setText("⏳ 正在恢复聊天记录…已恢复 " + restoreCount + " 条");
        restoreTip.setVisibility(View.VISIBLE);
    }

    private void buildChatsInto(LinearLayout content) {
        content.addView(topBar("灵珑"));
        content.addView(quickActions());

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(restoreTipView());
        int count = 0;
        for (final Friend f : friends) {
            list.addView(convRow(f.nickname, "u:" + f.wxid, lastMessage("u:" + f.wxid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openChat("u:" + f.wxid);
                        }
                    }));
            count++;
        }
        for (final Group gr : groups) {
            list.addView(convRow(gr.name, "g:" + gr.gid, lastMessage("g:" + gr.gid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openChat("g:" + gr.gid);
                        }
                    }));
            count++;
        }
        java.util.LinkedHashSet<String> strangers = new java.util.LinkedHashSet<String>();
        for (Msg m : messages) {
            if (!"user".equals(m.toType)) continue;
            String peer = m.from.equals(wxid) ? m.toId : m.from;
            if (findFriend(peer) == null) strangers.add(peer);
        }
        for (final String sid : strangers) {
            if (restoringHistory) break;   // 恢复中好友列表还没拉回来，别先冒出"未添加"
            list.addView(convRow("未添加 · " + sid, "u:" + sid, lastMessage("u:" + sid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            addStranger(sid);
                        }
                    }));
            count++;
        }
        if (count == 0) {
            TextView tip = new TextView(act);
            tip.setText("还没有会话\n点右上角 ＋ 加好友");
            tip.setTextSize(14);
            tip.setTextColor(dark ? 0xFF6E6E73 : 0xFFB2B2B2);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(80), 0, 0);
            list.addView(tip);
        }

        if (!updateChecked) {
            updateChecked = true;
            checkUpdate();
        }
    }

    /** 顶栏下方的快捷操作：加好友 / 建群 */
    private View quickActions() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), 0, dp(16), dp(10));

        TextView add = actionChip("＋ 加好友");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogAddFriend();
            }
        });
        row.addView(add, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView grp = actionChip("＋ 建群");
        grp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogCreateGroup();
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        glp.leftMargin = dp(10);
        row.addView(grp, glp);
        return row;
    }

    private TextView actionChip(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(9), dp(10), dp(9));
        tv.setTextColor(accent);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(19));
        bg.setColor(dark ? 0x1FFFFFFF : 0xFFEFF4FF);
        tv.setBackground(bg);
        return tv;
    }

    // ---------------- 通讯录 ----------------

    private void buildContactsInto(LinearLayout content) {
        content.addView(topBar("通讯录"));

        // 搜索框（V0.0.13：按昵称 / 专属号过滤好友）
        final EditText search = input("搜索好友（昵称或专属号）", "");
        search.setTextSize(14);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16);
        slp.rightMargin = dp(16);
        slp.bottomMargin = dp(6);
        content.addView(search, slp);

        ScrollView sv = new ScrollView(act);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fillContactRows(list, "");
        // 兜底自愈：每次进通讯录都和服务端对齐一次（有变化会自己重绘）
        refreshFriendProfiles();
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s2, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s2, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                fillContactRows(list, e.toString().trim());
            }
        });
    }

    /** 按关键字填充通讯录（空关键字 = 全部） */
    private void fillContactRows(LinearLayout list, String q) {
        list.removeAllViews();
        String kw = q == null ? "" : q.toLowerCase();

        if (kw.isEmpty()) {
            list.addView(listRow("👤", 0xFFF5A623, "新的朋友", "好友申请 / 按专属号添加", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showNewFriends();      // v0.1.4：好友要对方通过，申请在这里处理
                }
            }));
            list.addView(listRow("👥", 0xFF07C160, "群聊", groups.size() + " 个群", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showGroupList();          // 先看"我参与了哪些群"，进去才是建群
                }
            }));
            View gap = new View(act);
            list.addView(gap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        }

        int shown = 0;
        for (final Friend f : friends) {
            if (!kw.isEmpty()
                    && !f.nickname.toLowerCase().contains(kw)
                    && !f.wxid.toLowerCase().contains(kw)) {
                continue;
            }
            list.addView(listRow(null, 0, f.nickname, f.wxid, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    contactSheet(f);
                }
            }, "u:" + f.wxid));
            shown++;
        }

        TextView sec = new TextView(act);
        sec.setText(kw.isEmpty() ? "我的好友（" + friends.size() + "）"
                                 : "找到 " + shown + " 个");
        sec.setTextSize(12);
        sec.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        sec.setPadding(dp(18), dp(10), dp(18), dp(4));
        list.addView(sec, 0);

        if (shown == 0) {
            TextView tip = new TextView(act);
            tip.setText(kw.isEmpty() ? "还没有好友\n点右上角 ＋ 或「新的朋友」添加" : "没有匹配的好友");
            tip.setTextSize(14);
            tip.setTextColor(dark ? 0xFF6E6E73 : 0xFFB2B2B2);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(50), 0, 0);
            list.addView(tip);
        }
    }

    /** 群二维码：把群密钥用一次性口令加密后**写进二维码本身**，服务端只发入场 token（7 天有效） */
    private void showGroupQr(final Group g) {
        if (g.key == null) {
            toast("群密钥还没准备好，稍后再试");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = new byte[32];
                    new java.security.SecureRandom().nextBytes(raw);
                    final String secret = android.util.Base64.encodeToString(raw,
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
                    SecretKey ik = Crypto.keyFromSecret(secret);
                    final String wrapped = android.util.Base64.encodeToString(
                            Crypto.encryptBytes(ik, g.key.getEncoded()), android.util.Base64.NO_WRAP);
                    JSONObject r = api.post("/api/groups/" + g.gid + "/qr", new JSONObject());
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "生成失败"));
                    }
                    final String token = r.optString("token", "");
                    final String link = "linglum://gjoin?t=" + token + "&s=" + secret + "&w=" + wrapped;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showQrDialog(g, link);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("生成二维码失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showQrDialog(Group g, String link) {
        android.graphics.Bitmap qr = QrUtil.make(link, 720);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(20), dp(24), dp(10));
        if (qr != null) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setImageBitmap(qr);
            box.addView(iv, new LinearLayout.LayoutParams(dp(230), dp(230)));
        }
        TextView tip = new TextView(act);
        tip.setText("让好友用「发现 → 扫一扫」加入「" + g.name + "」\n二维码 7 天内有效");
        tip.setTextSize(13);
        tip.setTextColor(textSub());
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(12), 0, 0);
        box.addView(tip);
        new AlertDialog.Builder(act)
                .setTitle("群二维码")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 扫到群二维码：先确认群名，再用二维码里的口令解开群密钥 → 调接口入群 */
    private void joinGroupByQr(final String link) {
        final android.net.Uri u = android.net.Uri.parse(link);
        final String token = u.getQueryParameter("t");
        final String secret = u.getQueryParameter("s");
        final String wrapped = u.getQueryParameter("w");
        if (token == null || secret == null || wrapped == null) {
            toast("二维码不完整，请让群成员重新生成");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject info = api.get("/api/groups/join/info?token=" + token);
                    if (info.optInt("_http", 0) >= 400) {
                        throw new Exception(info.optString("error", "二维码无效"));
                    }
                    final String gid = info.optString("gid", "");
                    final String name = info.optString("name", "群聊");
                    final boolean already = info.optBoolean("is_member", false);
                    if (already) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("你已经在这个群里了");
                                refreshGroupsQuiet(new Runnable() {
                                    @Override
                                    public void run() {
                                        openChat("g:" + gid);
                                    }
                                });
                            }
                        });
                        return;
                    }
                    SecretKey ik = Crypto.keyFromSecret(secret);
                    final byte[] keyRaw = Crypto.decryptBytes(ik,
                            android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP));
                    JSONObject b = new JSONObject();
                    b.put("token", token);
                    b.put("wrapped", wrapped);
                    JSONObject r = api.post("/api/groups/join", b);
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "加入失败"));
                    }
                    prefs.edit().putString("gkey_" + gid,
                            android.util.Base64.encodeToString(keyRaw, android.util.Base64.NO_WRAP)).apply();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("已加入「" + name + "」");
                            refreshGroupsQuiet(new Runnable() {
                                @Override
                                public void run() {
                                    openChat("g:" + gid);
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("加入失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 通讯录 →「群聊」：我参与的所有群（微信里也是这样一层）；点一个直接进会话 */
    private void showGroupList() {
        pageId = page("glist", ANIM_FWD);
        reset();
        groupListPage = true;
        chatList = null;                 // 这一页没有聊天列表，别让轮询往旧视图塞消息
        chatScroll = null;
        rootCol.addView(subPageBar("群聊", new Runnable() {
            @Override
            public void run() {
                groupListPage = false;
                showTab(1);
            }
        }));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(18));
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(listRow("➕", 0xFF07C160, "发起群聊", "选好友建一个新群",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialogCreateGroup();
                    }
                }));

        if (groups.isEmpty()) {
            TextView tip = new TextView(act);
            tip.setText("还没有加入任何群聊\n点「发起群聊」建一个");
            tip.setTextSize(14);
            tip.setTextColor(dark ? 0xFF6E6E73 : 0xFFB2B2B2);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(50), 0, 0);
            list.addView(tip);
            return;
        }

        TextView sec = new TextView(act);
        sec.setText("我参与的群聊（" + groups.size() + "）");
        sec.setTextSize(12);
        sec.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        sec.setPadding(dp(18), dp(10), dp(18), dp(4));
        list.addView(sec, 0);

        for (final Group g : groups) {
            String sub = g.members.size() + " 人";
            if (isGroupOwner(g)) sub += " · 我是群主";
            else if (isGroupAdmin(g)) sub += " · 管理员";
            list.addView(listRow("👥", 0xFF07C160, g.name, sub, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    groupListPage = false;
                    openChat("g:" + g.gid);
                }
            }));
        }

        // 打开时对齐一次：别人新建/解散群，这里要能看到；只有群集合变了才重绘（避免死循环）
        final String sig0 = groupsSig();
        refreshGroupsQuiet(new Runnable() {
            @Override
            public void run() {
                if (!groupListPage) return;
                if (!groupsSig().equals(sig0)) showGroupList();
            }
        });
    }

    /** 通讯录一行：左侧头像 + 标题 + 副标题 */
    private View listRow(String emoji, int color, String title, String sub,
                         View.OnClickListener click) {
        return listRow(emoji, color, title, sub, click, null);
    }

    private View listRow(String emoji, int color, String title, String sub,
                         View.OnClickListener click, String avatarKey) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(14), dp(9));

        View av;
        if (avatarKey != null) {
            av = avatarView(title, avatarKey);
        } else {
            TextView ic = new TextView(act);
            ic.setText(emoji);
            ic.setTextSize(18);
            ic.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(color);
            ic.setBackground(bg);
            av = ic;
        }
        row.addView(av, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s2 = new TextView(act);
            s2.setText(sub);
            s2.setTextSize(12);
            s2.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            col.addView(s2);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(click);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        wrap.setBackground(cardBgDrawable(16));
        try {
            wrap.setElevation(dp(1));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(10);
        olp.rightMargin = dp(10);
        olp.topMargin = dp(3);
        olp.bottomMargin = dp(3);
        wrap.setLayoutParams(olp);
        return wrap;
    }

    /** 点通讯录里的人：发消息 / 资料 / 删好友 */
    private void contactSheet(final Friend f) {
        new AlertDialog.Builder(act)
                .setTitle(f.nickname)
                .setItems(new String[]{"发消息", "查看资料", "删除好友"},
                        new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) openChat("u:" + f.wxid);
                        else if (which == 1) showProfileDialog(f);
                        else dialogDeleteFriend(f);
                    }
                })
                .show();
    }

    /** 删好友：先问要不要连聊天记录一起清掉 */
    private void dialogDeleteFriend(final Friend f) {
        new AlertDialog.Builder(act)
                .setTitle("删除好友")
                .setMessage("「" + f.nickname + "」只会从你的通讯录里移除；对方那边还会保留你（和微信一致）。")
                .setItems(new String[]{"只删好友（保留聊天记录）", "删好友并清空聊天记录"},
                        new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        deleteFriend(f, which == 1);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 真正执行删除：**先让服务端确认删掉，再动本地**。
     * 以前是无论成功失败都先本地删：一旦请求失败，页面刷新又会从服务端把它加回来，
     * 用户看到的就是"删了又回来 / 删不掉"。现在失败就留存并明确报错。
     */
    private void deleteFriend(final Friend f, final boolean wipeHistory) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int http = 0;
                String err = null;
                try {
                    JSONObject r = api.delete("/api/friends/" + f.wxid);
                    http = r.optInt("_http", 0);
                    if (http >= 400) {
                        err = "HTTP " + http + " " + r.optString("error", "服务器删除失败");
                    }
                } catch (Exception e) {
                    err = (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                // 复查一次通讯录：服务端真的没有了才算删成功
                boolean gone = false;
                if (err == null) {
                    try {
                        JSONArray arr = api.get("/api/friends").optJSONArray("friends");
                        gone = true;
                        for (int i = 0; arr != null && i < arr.length(); i++) {
                            if (f.wxid.equals(arr.getJSONObject(i).optString("wxid"))) {
                                gone = false;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        gone = (http >= 200 && http < 300);   // 复查失败就按删除请求的结果算
                    }
                }
                try {
                    syncLog.append("删除好友 ").append(f.wxid).append(" → HTTP ")
                            .append(http).append(err == null ? " ok" : (" " + err)).append('\n');
                } catch (Exception ignored) {
                }
                final boolean okGone = gone;
                final String ferr = err;
                if (!okGone) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("删除失败：" + (ferr == null ? "服务器没删掉，稍后再试" : ferr));
                        }
                    });
                    return;      // 本地不动，避免"删了又回来"
                }
                friends.remove(f);
                saveFriends();
                // 服务端好友清单里也去掉，避免下次同步又被加回来
                try {
                    JSONArray srv = new JSONArray(prefs.getString("friends_srv", "[]"));
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < srv.length(); i++) {
                        String id = srv.optString(i, "");
                        if (!id.equals(f.wxid)) out.put(id);
                    }
                    prefs.edit().putString("friends_srv", out.toString()).apply();
                } catch (Exception ignored) {
                }
                if (wipeHistory) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        Msg m = messages.get(i);
                        if ("user".equals(m.toType) && convOf(m).equals("u:" + f.wxid)) {
                            messages.remove(i);
                        }
                    }
                    rewriteMessages();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(wipeHistory ? "已删除好友并清空聊天记录" : "已删除好友（聊天记录保留）");
                        if (openChat != null && openChat.equals("u:" + f.wxid)) openChat = null;
                        showTab(currentTab);
                    }
                });
            }
        }).start();
    }

    /** 把内存里的消息整份重写到本地文件（删记录用） */
    private void rewriteMessages() {
        File tmp = new File(act.getFilesDir(), "messages.jsonl.tmp");
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            for (Msg m : messages) {
                os.write((msgToJson(m) + "\n").getBytes("UTF-8"));
            }
            os.close();
            File dst = msgFile();
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } catch (Exception e) {
            try { tmp.delete(); } catch (Exception ignored) { }
        }
    }

    private String msgToJson(Msg m) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("from", m.from);
            o.put("to_type", m.toType);
            o.put("to_id", m.toId);
            o.put("payload", m.payload);
            o.put("sig", m.sig == null ? "" : m.sig);
            o.put("a", m.attach == null ? "" : m.attach);
            o.put("ts", m.ts);
            o.put("rc", m.recalled ? 1 : 0);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void showProfileDialog(Friend f) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(8));
        TextView t = new TextView(act);
        t.setText("昵称：" + f.nickname + "\n专属号：" + f.wxid
                + "\n安全码：" + Crypto.fingerprint(f.pubkey)
                + "\n\n（安全码可当面核对，防止有人冒充）");
        t.setTextSize(14);
        box.addView(t);
        new AlertDialog.Builder(act).setTitle("资料").setView(box)
                .setPositiveButton("好", null).show();
    }

    // ---------------- 发现 ----------------

    private void buildDiscoverInto(LinearLayout content) {
        content.addView(topBar("发现"));
        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(listRow("🌈", 0xFFE8590C, "朋友圈", "记录生活，看看好友的近况", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoments();
            }
        }));
        list.addView(listRow("📷", 0xFF3B7BE0, "扫一扫", "扫二维码加好友", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startActivityForResult(new Intent(act, QrScan.class), REQ_SCAN);
            }
        }));
    }

    // ---------------- 朋友圈 ----------------

    private void showMoments() {
        openChat = null;
        pageId = page("moments", ANIM_FWD);
        reset();
        momentsPage = true;   // 返回键 → 发现页签
        prefs.edit().putLong("moments_seen", System.currentTimeMillis()).apply();

        // 顶栏：左边返回、中间标题、右边发新动态
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(dark ? 0xFF1C1C1E : 0xFFEDEDED);
        head.setPadding(dp(4), dp(6) + statusBarH, dp(4), dp(6));
        TextView back = new TextView(act);
        back.setText("‹");
        back.setTextSize(27);
        back.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), 0, dp(12), dp(2));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingAnim = ANIM_BACK;
                showTab(2);
            }
        });
        head.addView(back);
        TextView title = new TextView(act);
        title.setText("朋友圈");
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(dark ? 0xFFF2F2F2 : 0xFF111111);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pub = new TextView(act);
        pub.setText("📷");
        pub.setTextSize(19);
        pub.setGravity(Gravity.CENTER);
        pub.setPadding(dp(14), 0, dp(14), dp(4));
        pub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMomentCompose();
            }
        });
        head.addView(pub);
        rootCol.addView(head);

        momentsScroll = new ScrollView(act);
        momentFeed = new LinearLayout(act);
        momentFeed.setOrientation(LinearLayout.VERTICAL);
        momentFeed.setPadding(0, dp(6), 0, dp(24));
        momentsScroll.addView(momentFeed);
        rootCol.addView(momentsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        loadMoments(false, 0);
    }

    private void loadMoments(final boolean more, final int keepY) {
        final long before = more && !moments.isEmpty()
                ? moments.get(moments.size() - 1).optLong("created", 0) : 0;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject r = api.get("/api/moments?limit=20"
                            + (before > 0 ? "&before=" + before : ""));
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "加载失败"));
                    JSONArray arr = r.optJSONArray("moments");
                    if (!more) moments.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) moments.add(arr.getJSONObject(i));
                    }
                    hasMoreMoments = r.optBoolean("has_more", false);
                    momentErr = null;
                } catch (Exception e) {
                    momentErr = e.getMessage();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        renderMoments(keepY);
                    }
                });
            }
        }).start();
    }

    private void renderMoments(final int keepY) {
        if (momentFeed == null) return;
        momentFeed.removeAllViews();
        if (momentErr != null) {
            TextView t = new TextView(act);
            t.setText("加载失败：" + momentErr + "\n点这里重试");
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            t.setPadding(dp(20), dp(40), dp(20), dp(40));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadMoments(false, 0);
                }
            });
            momentFeed.addView(t);
            return;
        }
        if (moments.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没有朋友圈\n点右上角 📷 发第一条");
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            t.setPadding(dp(20), dp(60), dp(20), dp(60));
            momentFeed.addView(t);
            return;
        }
        for (int i = 0; i < moments.size(); i++) {
            momentFeed.addView(buildMomentCard(moments.get(i)));
        }
        if (hasMoreMoments) {
            TextView moreBtn = new TextView(act);
            moreBtn.setText("查看更多");
            moreBtn.setTextSize(14);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setTextColor(0xFF576B95);
            moreBtn.setPadding(dp(20), dp(14), dp(20), dp(14));
            moreBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadMoments(true, momentsScroll == null ? 0 : momentsScroll.getScrollY());
                }
            });
            momentFeed.addView(moreBtn);
        }
        if (keepY > 0 && momentsScroll != null) {
            momentsScroll.post(new Runnable() {
                @Override
                public void run() {
                    momentsScroll.scrollTo(0, keepY);
                }
            });
        }
    }

    /** 解出这条朋友圈的密钥（作者本人或收到包裹密钥的好友才行） */
    private javax.crypto.SecretKey momentKey(JSONObject m) {
        if (m == null) return null;
        String mid = m.optString("mid");
        if (momentKeyCache.containsKey(mid)) return momentKeyCache.get(mid);
        String wrapped = m.optString("wrapped", "");
        if (wrapped.isEmpty() || myPriv == null) return null;
        String ownerPub = pubkeyOf(m.optString("author"));
        if (ownerPub == null || ownerPub.isEmpty()) return null;
        try {
            javax.crypto.SecretKey k = Crypto.unwrapKey(myPriv, ownerPub, wrapped);
            if (k != null) momentKeyCache.put(mid, k);
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean momentLikedByMe(JSONObject m) {
        JSONArray likes = m.optJSONArray("likes");
        if (likes == null) return false;
        for (int i = 0; i < likes.length(); i++) {
            if (likes.optJSONObject(i) != null && wxid.equals(likes.optJSONObject(i).optString("wxid"))) {
                return true;
            }
        }
        return false;
    }

    private View buildMomentCard(final JSONObject m) {
        final String mid = m.optString("mid");
        final String author = m.optString("author");
        String nick = m.optString("author_nick", author);
        if (nick == null || nick.isEmpty()) nick = author;

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg());
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(10), dp(5), dp(10), dp(5));
        card.setLayoutParams(clp);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));

        // 头部：头像 + 昵称 + 时间（自己的动态右边给「删除」）
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View av = avatarView(nick, "u:" + author);
        top.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        TextView nm = new TextView(act);
        nm.setText(nick);
        nm.setTextSize(15);
        nm.setTextColor(0xFF576B95);
        TextView tm = new TextView(act);
        tm.setText(momentTime(m.optLong("created", 0)));
        tm.setTextSize(12);
        tm.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        col.addView(nm);
        col.addView(tm);
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (author.equals(wxid)) {
            TextView del = new TextView(act);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            del.setPadding(dp(10), 0, dp(2), 0);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteMoment(mid);
                }
            });
            top.addView(del);
        }
        card.addView(top);

        // 正文
        javax.crypto.SecretKey k = momentKey(m);
        String text = null;
        boolean locked = false;
        if (k != null) {
            try {
                text = Crypto.decrypt(k, m.optString("text_enc", ""));
            } catch (Exception e) {
                locked = true;
            }
        } else {
            locked = true;
        }
        if (locked) {
            TextView t = new TextView(act);
            t.setText("🔒 无法解密（发布时你还不是好友）");
            t.setTextSize(13);
            t.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            t.setPadding(0, dp(8), 0, 0);
            card.addView(t);
        } else if (text != null && !text.trim().isEmpty()) {
            TextView body = new TextView(act);
            body.setText(text.trim());
            body.setTextSize(15);
            body.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
            body.setLineSpacing(dp(3), 1f);
            body.setPadding(0, dp(8), 0, 0);
            card.addView(body);
        }

        // 图片网格
        int n = m.optInt("n_images", 0);
        if (n > 0 && k != null) {
            card.addView(buildMomentGrid(mid, n, k));
        }

        // 赞 / 评论
        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.END);
        acts.setPadding(0, dp(8), 0, 0);
        TextView likeBtn = new TextView(act);
        likeBtn.setText(momentLikedByMe(m) ? "❤ 取消" : "♡ 赞");
        likeBtn.setTextSize(13);
        likeBtn.setTextColor(0xFF576B95);
        likeBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        likeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMomentLike(mid);
            }
        });
        acts.addView(likeBtn);
        TextView cmtBtn = new TextView(act);
        cmtBtn.setText("💬 评论");
        cmtBtn.setTextSize(13);
        cmtBtn.setTextColor(0xFF576B95);
        cmtBtn.setPadding(dp(10), dp(4), dp(2), dp(4));
        cmtBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                momentCommentDialog(m);
            }
        });
        acts.addView(cmtBtn);
        card.addView(acts);

        // 点赞列表
        JSONArray likes = m.optJSONArray("likes");
        if (likes != null && likes.length() > 0) {
            StringBuilder sb = new StringBuilder("❤ ");
            for (int i = 0; i < likes.length(); i++) {
                JSONObject l = likes.optJSONObject(i);
                if (l == null) continue;
                if (sb.length() > 2) sb.append("、");
                sb.append(l.optString("nickname", l.optString("wxid")));
            }
            TextView lk = new TextView(act);
            lk.setText(sb.toString());
            lk.setTextSize(13);
            lk.setTextColor(0xFF576B95);
            lk.setPadding(0, dp(6), 0, 0);
            card.addView(lk);
        }

        // 评论列表
        JSONArray cms = m.optJSONArray("comments");
        if (cms != null && cms.length() > 0) {
            for (int i = 0; i < cms.length(); i++) {
                JSONObject c = cms.optJSONObject(i);
                if (c == null) continue;
                String ctext;
                try {
                    ctext = Crypto.decrypt(k, c.optString("text_enc", ""));
                } catch (Exception e) {
                    ctext = "🔒 无法解密";
                }
                TextView cv = new TextView(act);
                android.text.SpannableString ss = new android.text.SpannableString(
                        c.optString("nickname", c.optString("wxid")) + "：" + ctext);
                try {
                    ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF576B95), 0,
                            (c.optString("nickname", c.optString("wxid"))) .length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } catch (Exception ignored) {
                }
                cv.setText(ss);
                cv.setTextSize(13);
                cv.setTextColor(dark ? 0xFFCFCFCF : 0xFF333333);
                cv.setPadding(0, dp(5), 0, 0);
                card.addView(cv);
            }
        }
        return card;
    }

    private View buildMomentGrid(final String mid, final int n, final javax.crypto.SecretKey key) {
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(8), 0, 0);
        int cols = n == 1 ? 1 : (n == 4 ? 2 : 3);
        int cell = n == 1 ? Math.min(dp(200), momentCell() * 2) : momentCell();
        for (int i = 0; i < n; i += cols) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + cols, n); j++) {
                android.widget.ImageView iv = new android.widget.ImageView(act);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                GradientDrawable ph = new GradientDrawable();
                ph.setColor(dark ? 0xFF2A2A2C : 0xFFEFEFEF);
                ph.setCornerRadius(dp(6));
                iv.setBackground(ph);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                lp.setMargins(0, 0, dp(5), dp(5));
                row.addView(iv, lp);
                loadMomentImg(mid, j, iv, key);
            }
            grid.addView(row);
        }
        return grid;
    }

    private int momentCell() {
        int w = act.getResources().getDisplayMetrics().widthPixels;
        int usable = w - dp(10) * 2 - dp(14) * 2;
        int c = (usable - dp(5) * 3) / 3;
        return Math.max(dp(72), Math.min(c, dp(112)));
    }

    private void loadMomentImg(final String mid, final int idx,
                               final android.widget.ImageView iv, final javax.crypto.SecretKey key) {
        final String ck = mid + "#" + idx;
        iv.setTag(ck);
        android.graphics.Bitmap b = momentImgCache.get(ck);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        imgPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] enc = api.getBytes("/api/moments/" + mid + "/img/" + idx);
                    if (enc == null || enc.length == 0) return;
                    byte[] plain = Crypto.decryptBytes(key, enc);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(plain, 0, plain.length, o);
                    int target = Math.max(240, dp(112) * 2);
                    int sample = 1;
                    while (o.outWidth > 0 && Math.max(o.outWidth, o.outHeight) / sample > target) sample *= 2;
                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    final android.graphics.Bitmap bmp =
                            android.graphics.BitmapFactory.decodeByteArray(plain, 0, plain.length, o2);
                    if (bmp == null) return;
                    momentImgCache.put(ck, bmp);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ck.equals(iv.getTag())) iv.setImageBitmap(bmp);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void toggleMomentLike(final String mid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject r = api.post("/api/moments/" + mid + "/like", new JSONObject());
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "操作失败"));
                    final boolean liked = r.optBoolean("liked", false);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(liked ? "❤ 已赞" : "已取消");
                            loadMoments(false, momentsScroll == null ? 0 : momentsScroll.getScrollY());
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void momentCommentDialog(final JSONObject m) {
        final String mid = m.optString("mid");
        final javax.crypto.SecretKey k = momentKey(m);
        if (k == null) {
            toast("解不开这条朋友圈的密钥");
            return;
        }
        final EditText et = input("说点什么…", "");
        new AlertDialog.Builder(act)
                .setTitle("评论")
                .setView(et)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String txt = et.getText().toString().trim();
                        if (txt.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("text", Crypto.encrypt(k, txt));
                                    JSONObject r = api.post("/api/moments/" + mid + "/comment", b);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "评论失败"));
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            loadMoments(false, momentsScroll == null ? 0 : momentsScroll.getScrollY());
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("评论失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteMoment(final String mid) {
        new AlertDialog.Builder(act)
                .setTitle("删除这条朋友圈？")
                .setMessage("删除后好友也看不到了")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    JSONObject r = api.delete("/api/moments/" + mid);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "删除失败"));
                                    momentKeyCache.remove(mid);
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("已删除");
                                            loadMoments(false, 0);
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("删除失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String momentTime(long ms) {
        if (ms <= 0) return "";
        long now = System.currentTimeMillis();
        long d = Math.max(0, now - ms);
        if (d < 60 * 1000) return "刚刚";
        if (d < 60 * 60 * 1000) return (d / 60000) + " 分钟前";
        java.util.Calendar c1 = java.util.Calendar.getInstance();
        java.util.Calendar c2 = java.util.Calendar.getInstance();
        c1.setTimeInMillis(ms);
        c2.setTimeInMillis(now);
        String hm = String.format("%02d:%02d",
                c1.get(java.util.Calendar.HOUR_OF_DAY), c1.get(java.util.Calendar.MINUTE));
        boolean sameDay = c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                && c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return hm;
        String md = String.format("%02d-%02d %s",
                c1.get(java.util.Calendar.MONTH) + 1, c1.get(java.util.Calendar.DAY_OF_MONTH), hm);
        return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                ? md : (c1.get(java.util.Calendar.YEAR) + "-" + md);
    }

    // ---- 发朋友圈 ----

    private void showMomentCompose() {
        openChat = null;
        pageId = page("compose", ANIM_FWD);
        reset();
        composePage = true;   // 返回键 → 朋友圈
        composePics.clear();

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(dark ? 0xFF1C1C1E : 0xFFEDEDED);
        head.setPadding(dp(4), dp(6) + statusBarH, dp(4), dp(6));
        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        cancel.setPadding(dp(14), dp(6), dp(14), dp(6));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingAnim = ANIM_BACK;
                showMoments();
            }
        });
        head.addView(cancel);
        TextView title = new TextView(act);
        title.setText("发表朋友圈");
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(dark ? 0xFFF2F2F2 : 0xFF111111);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = new TextView(act);
        send.setText("发表");
        send.setTextSize(15);
        send.setTextColor(0xFF07C160);
        send.setPadding(dp(14), dp(6), dp(14), dp(6));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                postMoment();
            }
        });
        head.addView(send);
        rootCol.addView(head);

        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(20));

        composeText = new EditText(act);
        composeText.setHint("这一刻的想法…");
        composeText.setTextSize(16);
        composeText.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        composeText.setHintTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        composeText.setBackgroundColor(Color.TRANSPARENT);
        composeText.setGravity(Gravity.TOP | Gravity.START);
        composeText.setMinHeight(dp(110));
        composeText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(composeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        composePicBox = new LinearLayout(act);
        composePicBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(composePicBox);

        TextView add = new TextView(act);
        add.setText("＋ 图片（最多 9 张）");
        add.setTextSize(14);
        add.setTextColor(0xFF576B95);
        add.setPadding(dp(2), dp(10), dp(2), dp(10));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (composePics.size() >= 9) {
                    toast("最多 9 张");
                    return;
                }
                pickMomentImages();
            }
        });
        box.addView(add);

        TextView tip = new TextView(act);
        tip.setText("图片会压缩到 1080px 再加密发送；只有你的好友能看。");
        tip.setTextSize(12);
        tip.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        tip.setPadding(dp(2), dp(4), dp(2), 0);
        box.addView(tip);

        sv.addView(box);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void pickMomentImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            act.startActivityForResult(Intent.createChooser(i, "选图片"), REQ_MOMENT_IMG);
        } catch (Exception e) {
            toast("打不开相册");
        }
    }

    private void renderComposePics() {
        if (composePicBox == null) return;
        composePicBox.removeAllViews();
        int cols = 3;
        int cell = Math.min(dp(96), (act.getResources().getDisplayMetrics().widthPixels
                - dp(12) * 2 - dp(5) * 3) / 3);
        for (int i = 0; i < composePics.size(); i += cols) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(5), 0, 0);
            for (int j = i; j < Math.min(i + cols, composePics.size()); j++) {
                android.widget.ImageView iv = new android.widget.ImageView(act);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                GradientDrawable ph = new GradientDrawable();
                ph.setColor(dark ? 0xFF2A2A2C : 0xFFEFEFEF);
                ph.setCornerRadius(dp(6));
                iv.setBackground(ph);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                lp.setMargins(0, 0, dp(5), 0);
                row.addView(iv, lp);
                loadPickThumb(composePics.get(j), iv);
            }
            composePicBox.addView(row);
        }
    }

    private void loadPickThumb(final android.net.Uri uri, final android.widget.ImageView iv) {
        imgPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in = act.getContentResolver().openInputStream(uri);
                    android.graphics.BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > 240) sample *= 2;
                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    in = act.getContentResolver().openInputStream(uri);
                    final android.graphics.Bitmap b =
                            android.graphics.BitmapFactory.decodeStream(in, null, o2);
                    in.close();
                    if (b == null) return;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(b);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private byte[] compressMoment(android.net.Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = act.getContentResolver().openInputStream(uri);
        android.graphics.BitmapFactory.decodeStream(in, null, o);
        in.close();
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("图片读不出来");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 1080) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = act.getContentResolver().openInputStream(uri);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (bmp == null) throw new Exception("图片读不出来");
        int w = bmp.getWidth(), h = bmp.getHeight(), mx = Math.max(w, h);
        if (mx > 1080) {
            float sc = 1080f / mx;
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                    Math.max(1, (int) (w * sc)), Math.max(1, (int) (h * sc)), true);
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        byte[] raw = bos.toByteArray();
        if (raw.length > 2 * 1024 * 1024) {
            bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, bos);
            raw = bos.toByteArray();
        }
        if (raw.length > 2 * 1024 * 1024) throw new Exception("图片太大（换一张试试）");
        return raw;
    }

    private void postMoment() {
        final String txt = composeText == null ? "" : composeText.getText().toString().trim();
        if (txt.isEmpty() && composePics.isEmpty()) {
            toast("写点什么或者选张图吧");
            return;
        }
        final java.util.List<android.net.Uri> pics =
                new java.util.ArrayList<android.net.Uri>(composePics);
        toast("正在发布…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    javax.crypto.SecretKey key = Crypto.newGroupKey();
                    JSONObject body = new JSONObject();
                    if (!txt.isEmpty()) body.put("text", Crypto.encrypt(key, txt));
                    JSONArray imgs = new JSONArray();
                    for (int i = 0; i < pics.size(); i++) {
                        byte[] jpg = compressMoment(pics.get(i));
                        imgs.put(android.util.Base64.encodeToString(
                                Crypto.encryptBytes(key, jpg), android.util.Base64.NO_WRAP));
                    }
                    if (imgs.length() > 0) body.put("images", imgs);

                    // 用最新公钥逐个包裹（先拉一次好友列表，避免拿旧公钥导致对方解不开）
                    String myPub = pubkeyB64;
                    if (myPub == null || myPub.isEmpty()) {
                        JSONObject me = api.get("/api/me");
                        myPub = me.optString("pubkey", "");
                        if (!myPub.isEmpty()) pubkeyB64 = myPub;
                    }
                    JSONObject keys = new JSONObject();
                    if (!myPub.isEmpty()) {
                        keys.put(wxid, Crypto.wrapKeyForMember(key, myPriv, myPub));
                    }
                    JSONObject fr = api.get("/api/friends");
                    JSONArray fa = fr.optJSONArray("friends");
                    if (fa != null) {
                        for (int i = 0; i < fa.length(); i++) {
                            JSONObject f = fa.optJSONObject(i);
                            if (f == null) continue;
                            String fw = f.optString("wxid");
                            String fp = f.optString("pubkey");
                            if (fw.isEmpty() || fp.isEmpty() || fw.equals(wxid)) continue;
                            try {
                                keys.put(fw, Crypto.wrapKeyForMember(key, myPriv, fp));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (keys.length() == 0) throw new Exception("还没有可分享的好友");
                    body.put("keys", keys);

                    JSONObject r = api.post("/api/moments", body);
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "发布失败"));
                    String mid = r.optString("mid", "");
                    if (!mid.isEmpty()) momentKeyCache.put(mid, key);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("✅ 已发表");
                            pendingAnim = ANIM_BACK;   // 发表完回朋友圈 = 返回
                            showMoments();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("发布失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- 我 ----------------

    private void buildMeInto(LinearLayout content) {
        content.addView(topBar("我"));
        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 我的名片
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        View av = avatarView(nickname, "me");
        card.addView(av, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView n = new TextView(act);
        n.setText(nickname == null || nickname.isEmpty() ? wxid : nickname);
        n.setTextSize(19);
        n.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        TextView id = new TextView(act);
        id.setText("专属号：" + wxid);
        id.setTextSize(13);
        id.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        col.addView(n);
        col.addView(id);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogEditProfile();
            }
        });
        list.addView(card);

        View gap0 = new View(act);
        list.addView(gap0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        list.addView(listRow("📱", 0xFF07C160, "我的二维码", "让别人扫你加好友", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMyQr();
            }
        }));
        list.addView(listRow("🖼", 0xFF3B7BE0, "换头像", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAvatar();
            }
        }));
        list.addView(listRow("✏️", 0xFF12B886, "改昵称", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogEditNickname();
            }
        }));
        View gap1 = new View(act);
        list.addView(gap1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        list.addView(listRow("🧩", 0xFF5856D6, "热更新", "小版本免安装，重启生效", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogHotfix();
            }
        }));
        list.addView(listRow("⚙️", 0xFF8A8A8E, "设置", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogSettings();
            }
        }));
        list.addView(listRow("🆘", 0xFFE8590C, "帮朋友找回", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogApproveFriend();
            }
        }));
        list.addView(listRow("📋", 0xFF9C36B5, "同步日志", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSyncLog();
            }
        }));
        list.addView(listRow("🚪", 0xFFFA5151, "退出登录", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logout();
            }
        }));
    }

    // ---------------- 通讯录 / 头像 同步 ----------------

    private void addFriendRemote(final String id) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("wxid", id);
                    api.post("/api/friends", b);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** 从服务端拉通讯录：补上缺的好友、更新昵称/头像版本/公钥 */
    private void refreshFriendProfiles() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject r = api.get("/api/friends");
                    JSONArray arr = r.optJSONArray("friends");
                    if (arr == null) return;
                    boolean changed = false;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String id = o.getString("wxid");
                        Friend f = findFriend(id);
                        if (f == null) {
                            f = new Friend();
                            f.wxid = id;
                            f.nickname = o.optString("nickname", id);
                            f.pubkey = o.optString("pubkey", "");
                            f.ver = o.optInt("pubkey_ver", 1);
                            f.avatarVer = o.optInt("avatar_ver", 0);
                            friends.add(f);
                            changed = true;
                        } else {
                            String nn = o.optString("nickname", f.nickname);
                            String pk = o.optString("pubkey", f.pubkey);
                            int av = o.optInt("avatar_ver", f.avatarVer);
                            if (!nn.equals(f.nickname) || !pk.equals(f.pubkey) || av != f.avatarVer) {
                                if (!pk.equals(f.pubkey)) rememberOldPub(f, f.pubkey);
                                f.nickname = nn;
                                f.pubkey = pk;
                                f.ver = o.optInt("pubkey_ver", f.ver);
                                f.avatarVer = av;
                                changed = true;
                            }
                        }
                    }
                    // 服务端通讯录是权威：上次同步过、这次没了的好友 → 本地也移除
                    // （对方把我删了 / 我在别的设备上删了，这边要一起消失）
                    java.util.LinkedHashSet<String> nowIds = new java.util.LinkedHashSet<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        nowIds.add(arr.getJSONObject(i).getString("wxid"));
                    }
                    java.util.LinkedHashSet<String> prevIds = new java.util.LinkedHashSet<String>();
                    try {
                        JSONArray prev = new JSONArray(prefs.getString("friends_srv", "[]"));
                        for (int i = 0; i < prev.length(); i++) prevIds.add(prev.optString(i, ""));
                    } catch (Exception ignored) {
                    }
                    for (String id : prevIds) {
                        if (!nowIds.contains(id)) {
                            Friend gone = findFriend(id);
                            if (gone != null) {
                                friends.remove(gone);
                                changed = true;
                                syncLog.append("好友已从服务端移除：").append(id).append('\n');
                            }
                        }
                    }
                    JSONArray outIds = new JSONArray();
                    for (String id : nowIds) outIds.put(id);
                    prefs.edit().putString("friends_srv", outIds.toString()).apply();

                    if (changed) {
                        saveFriends();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (openChat == null) showTab(currentTab);
                            }
                        });
                    }
                    syncAvatars();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** 下载好友/自己的头像（按 avatar_ver 判断要不要重新下） */
    private void syncAvatars() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean changed = false;
                try {
                    new File(act.getFilesDir(), "avatars").mkdirs();
                } catch (Exception ignored) {
                }
                java.util.List<String> todo = new ArrayList<String>();
                java.util.List<Integer> vers = new ArrayList<Integer>();
                for (Friend f : new ArrayList<Friend>(friends)) {
                    todo.add(f.wxid);
                    vers.add(f.avatarVer);
                }
                todo.add(wxid);
                vers.add(prefs.getInt("my_avatar_ver", 0));
                for (int i = 0; i < todo.size(); i++) {
                    String id = todo.get(i);
                    if (id == null || id.isEmpty()) continue;
                    int ver = vers.get(i);
                    if (prefs.getInt("avv_" + id, -1) == ver) continue;
                    try {
                        byte[] b = api.getBytes("/api/avatar/" + id);
                        File f2 = avatarFile(id);
                        if (b == null || b.length == 0) {
                            if (f2.exists()) f2.delete();
                        } else {
                            FileOutputStream os = new FileOutputStream(f2);
                            os.write(b);
                            os.close();
                            changed = true;
                        }
                        dropAvatarCache(id);          // 头像换了必须让缓存失效
                        prefs.edit().putInt("avv_" + id, ver).apply();
                    } catch (Exception ignored) {
                    }
                }
                if (changed) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (openChat == null) showTab(currentTab);
                        }
                    });
                }
            }
        }).start();
    }

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        act.startActivityForResult(i, REQ_AVATAR);
    }

    /** 选好的头像：压到 256px → base64 → 上传 */
    private void uploadAvatar(final android.net.Uri uri) {
        toast("正在上传头像…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in0 = act.getContentResolver().openInputStream(uri);
                    android.graphics.BitmapFactory.decodeStream(in0, null, o);
                    in0.close();
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > 512) sample *= 2;
                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    InputStream in1 = act.getContentResolver().openInputStream(uri);
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in1, null, o2);
                    in1.close();
                    if (bmp == null) throw new Exception("图片读不出来");
                    int side = Math.min(bmp.getWidth(), bmp.getHeight());
                    android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(bmp,
                            (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side);
                    android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sq, 256, 256, true);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos);
                    byte[] raw = bos.toByteArray();
                    if (raw.length > 400 * 1024) throw new Exception("图片太大");

                    JSONObject b = new JSONObject();
                    b.put("avatar", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP));
                    JSONObject r = api.post("/api/profile", b);
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "上传失败"));
                    int ver = r.optInt("avatar_ver", 0);
                    prefs.edit().putInt("my_avatar_ver", ver).putInt("avv_" + wxid, -1).apply();
                    dropAvatarCache(wxid);          // 自己换了头像：丢掉旧缓存
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("✅ 头像已更新");
                            syncAvatars();
                            showTab(currentTab);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("头像上传失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void dialogEditNickname() {
        final EditText et = input("新昵称", nickname);
        new AlertDialog.Builder(act)
                .setTitle("改昵称")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String nn = et.getText().toString().trim();
                        if (nn.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("nickname", nn);
                                    JSONObject r = api.post("/api/profile", b);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "保存失败"));
                                    nickname = nn;
                                    prefs.edit().putString("nickname", nn).apply();
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("✅ 昵称已更新");
                                            showTab(currentTab);
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dialogEditProfile() {
        new AlertDialog.Builder(act)
                .setTitle("我的名片")
                .setItems(new String[]{"换头像", "改昵称"}, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) pickAvatar();
                        else dialogEditNickname();
                    }
                })
                .show();
    }

    /** 极简顶栏：标题 + ＋（菜单） */
    private View topBar(String title) {
        android.widget.Toolbar tb = new android.widget.Toolbar(act);
        tb.setTitle(title);
        tb.setTitleTextColor(textMain());
        tb.setBackground(null);        // 玻璃底交给 holder，整条含状态栏那截同色，不出拼缝
        tb.setElevation(0);            // 不要阴影，改成玻璃质感
        tb.setMinimumHeight(dp(56));

        TextView plus = new TextView(act);
        plus.setText("＋");
        plus.setTextSize(22);
        plus.setTextColor(textMain());
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(rippleBg());
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogMenu();
            }
        });
        tb.addView(plus, new android.widget.Toolbar.LayoutParams(
                dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));

        LinearLayout holder = new LinearLayout(act);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackground(glassBar());        // 背景画到状态栏后面 = "补齐"
        holder.setPadding(0, statusBarH, 0, 0);  // 标题/＋让下来，不被状态栏压住
        holder.addView(tb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return holder;
    }

    /** 右上角 ＋ 菜单 */
    private void dialogMenu() {
        final String[] items = {"加好友", "建群", "帮朋友找回", "设置", "同步日志", "退出登录"};
        new AlertDialog.Builder(act)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) dialogAddFriend();
                        else if (which == 1) dialogCreateGroup();
                        else if (which == 2) dialogApproveFriend();
                        else if (which == 3) dialogSettings();
                        else if (which == 4) showSyncLog();
                        else logout();
                    }
                })
                .show();
    }

    private void showSyncLog() {
        new AlertDialog.Builder(act)
                .setTitle("同步日志")
                .setMessage(syncLog.length() == 0 ? "（暂无记录）" : syncLog.toString())
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("sync log", syncLog.toString()));
                        toast("已复制");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------- 平板双栏 ----------------

    /** 横屏平板才分两栏（跟微信 Pad 一样：左边整列列表带底部页签，右边聊天） */
    private boolean twoPane() {
        if (!tablet) return false;
        int w = act.getResources().getConfiguration().screenWidthDp;
        int h = act.getResources().getConfiguration().screenHeightDp;
        return w > h;
    }

    /** 只重填左侧列表（保持右侧聊天区不动，避免闪） */
    private void fillWideLeft() {
        if (wideLeft == null) return;
        wideLeft.removeAllViews();
        if (currentTab == 1) buildContactsInto(wideLeft);
        else if (currentTab == 2) buildDiscoverInto(wideLeft);
        else if (currentTab == 3) buildMeInto(wideLeft);
        else buildSidebar(wideLeft);
    }

    /** 只改左侧页签的颜色，不重建视图 */
    private void paintRail() {
        if (wideRail == null) return;
        for (int i = 0; i < wideRail.getChildCount(); i++) {
            View it = wideRail.getChildAt(i);
            if (!(it instanceof LinearLayout)) continue;
            LinearLayout item = (LinearLayout) it;
            int c = (i == currentTab) ? accent : textSub();
            for (int j = 0; j < item.getChildCount(); j++) {
                View ch = item.getChildAt(j);
                if (ch instanceof TextView) ((TextView) ch).setTextColor(c);
            }
        }
    }

    private void showWideLayout() {
        rootCol.setPadding(0, 0, 0, 0);   // rail/左顶栏/聊天头各自把背景画进状态栏

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        rootCol.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ① 最左：竖排页签（把底部那排挪到左边）
        LinearLayout rail = new LinearLayout(act);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(dark ? 0xFF141418 : 0xFFFFFFFF);
        rail.setPadding(0, dp(16) + statusBarH, 0, 0);   // 页签让下来，白底画到顶
        row.addView(rail, new LinearLayout.LayoutParams(dp(70),
                ViewGroup.LayoutParams.MATCH_PARENT));
        String[] icons = {"💬", "👥", "🧭", "👤"};
        String[] names = {"微信", "通讯录", "发现", "我"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(13), 0, dp(13));
            int c = (i == currentTab) ? 0xFF07C160 : (dark ? 0xFF8A8A8E : 0xFF9A9A9A);
            TextView ic = new TextView(act);
            ic.setText(icons[i]);
            ic.setTextSize(20);
            ic.setGravity(Gravity.CENTER);
            ic.setTextColor(c);
            TextView tx = new TextView(act);
            tx.setText(names[i]);
            tx.setTextSize(10.5f);
            tx.setGravity(Gravity.CENTER);
            tx.setTextColor(c);
            tx.setPadding(0, dp(3), 0, 0);
            item.addView(ic);
            item.addView(tx);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == currentTab) return;
                    if (twoPane() && wideLeft != null) {
                        // ⚠️ 只重填左栏 + 换页签颜色，不重建整个界面
                        // （之前每次 showTab → reset() 把右侧聊天区也重建，视觉上就"颤一下"）
                        currentTab = idx;
                        paintRail();
                        fillWideLeft();
                    } else {
                        showTab(idx);
                    }
                }
            });
            rail.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        View rline = new View(act);
        rline.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
        row.addView(rline, new LinearLayout.LayoutParams(1,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ② 列表列：窄一些（最多 320dp / 屏宽 32%）
        int leftPx = dp(300);
        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(dark ? 0xFF141418 : 0xFFFFFFFF);
        row.addView(left, new LinearLayout.LayoutParams(leftPx,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View vline = new View(act);
        vline.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
        row.addView(vline, new LinearLayout.LayoutParams(1,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 右：聊天区
        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(dark ? 0xFF111113 : 0xFFEDEDED);   // 与消息区同色，缝隙隐形
        right.setPadding(0, 0, 0, 0);   // 聊天铺满，不再留 8dp 白边
        row.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        wideLeft = left;
        wideRail = rail;
        fillWideLeft();

        if (openChat == null) {
            LinearLayout holder = new LinearLayout(act);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setGravity(Gravity.CENTER);
            TextView big = new TextView(act);
            big.setText("💬");
            big.setTextSize(56);
            big.setGravity(Gravity.CENTER);
            big.setAlpha(0.25f);
            holder.addView(big);
            TextView tip = new TextView(act);
            tip.setText("选择一个会话开始聊天");
            tip.setTextSize(14);
            tip.setTextColor(0xFF9AA4B2);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(10), 0, 0);
            holder.addView(tip);
            right.addView(holder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            buildChatInto(right, false);
        }

        if (!updateChecked) {
            updateChecked = true;
            checkUpdate();
        }
    }

    private void buildSidebar(LinearLayout host) {
        host.addView(topBar("灵珑"));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        host.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(restoreTipView());
        int count = 0;
        for (final Friend f : friends) {
            list.addView(convRow(f.nickname, "u:" + f.wxid, lastMessage("u:" + f.wxid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openChat("u:" + f.wxid);
                        }
                    }));
            count++;
        }
        for (final Group gr : groups) {
            list.addView(convRow(gr.name, "g:" + gr.gid, lastMessage("g:" + gr.gid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openChat("g:" + gr.gid);
                        }
                    }));
            count++;
        }
        java.util.LinkedHashSet<String> strangers = new java.util.LinkedHashSet<String>();
        for (Msg m : messages) {
            if (!"user".equals(m.toType)) continue;
            String peer = m.from.equals(wxid) ? m.toId : m.from;
            if (findFriend(peer) == null) strangers.add(peer);
        }
        for (final String sid : strangers) {
            if (restoringHistory) break;   // 恢复中好友列表还没拉回来，别先冒出"未添加"
            list.addView(convRow("未添加 · " + sid, "u:" + sid, lastMessage("u:" + sid),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            addStranger(sid);
                        }
                    }));
            count++;
        }
        if (count == 0) {
            TextView tip = new TextView(act);
            tip.setText("还没有会话\n点 ＋ 加好友");
            tip.setTextSize(14);
            tip.setTextColor(dark ? 0xFF6E6E73 : 0xFFB2B2B2);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(60), 0, 0);
            list.addView(tip);
        }
    }

    private String lastMessage(String convKey) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (convOf(m).equals(convKey)) {
                String t = m.plain != null ? m.plain : "🔒 无法解密（换密钥前的旧消息）";
                return (m.from.equals(wxid) ? "我：" : "") + t;
            }
        }
        return "";
    }

    private String convOf(Msg m) {
        if ("group".equals(m.toType)) return "g:" + m.toId;
        return "u:" + (m.from.equals(wxid) ? m.toId : m.from);
    }

    private View convRow(String title, String convKey, String preview, View.OnClickListener click) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackgroundColor(Color.TRANSPARENT);

        int unread = unreadOf(convKey);
        row.addView(avatarWithBadge(title, convKey, unread),
                new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(10), 0);
        TextView n = new TextView(act);
        n.setText(title);
        n.setTextSize(16);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        TextView p = new TextView(act);
        p.setText(preview);
        p.setTextSize(13);
        p.setMaxLines(1);
        p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.setTextColor(dark ? 0xFF8A8A8E : 0xFF9A9A9A);
        col.addView(n);
        col.addView(p);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(act);
        time.setText(lastTime(convKey));
        time.setTextSize(11);
        time.setTextColor(textSub());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        row.addView(time, tlp);

        row.setOnClickListener(click);
        wrap.addView(row);

        wrap.setBackground(cardBgDrawable(16));
        try {
            wrap.setElevation(dp(1));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(10);
        olp.rightMargin = dp(10);
        olp.topMargin = dp(3);
        olp.bottomMargin = dp(3);
        wrap.setLayoutParams(olp);
        return wrap;
    }

    /** 头像：圆角方形（微信风格），取名字首字 + 按 id 分配颜色 */
    private View avatarView(String name, String key) {
        String wxid0 = wxidOfKey(key);
        android.graphics.Bitmap bmp = wxid0 == null ? null : loadAvatar(wxid0);
        if (bmp != null) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setImageBitmap(bmp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            return iv;
        }
        TextView tv = new TextView(act);
        String ch = (name == null || name.isEmpty()) ? "?" : name.substring(0, 1);
        tv.setText(ch);
        tv.setTextSize(18);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        int[] palette = {0xFF12A37F, 0xFF4C6FFF, 0xFFE8890C, 0xFF9C36B5, 0xFF0C8599, 0xFFD6336C};
        int idx = Math.abs((key == null ? "" : key).hashCode()) % palette.length;
        bg.setColor(palette[idx]);
        tv.setBackground(bg);
        return tv;
    }

    /** "u:wxid" → wxid；自己的 "me" → 自己；群没有头像 */
    private String wxidOfKey(String key) {
        if (key == null) return null;
        if (key.startsWith("u:")) return key.substring(2);
        if ("me".equals(key)) return wxid;
        return null;
    }

    private File avatarFile(String wxid0) {
        return new File(new File(act.getFilesDir(), "avatars"), wxid0 + ".png");
    }

    /** 读本地缓存头像，并按 8dp 圆角裁一下（微信那种圆角方块） */
    private android.graphics.Bitmap loadAvatar(String wxid0) {
        if (wxid0 == null || wxid0.isEmpty()) return null;
        synchronized (avatarCache) {                       // 命中缓存：同一张头像只解码一次
            if (avatarCache.containsKey(wxid0)) return avatarCache.get(wxid0);
            if (avatarMissing.contains(wxid0)) return null;
        }
        android.graphics.Bitmap out = decodeAvatarFile(avatarFile(wxid0));
        synchronized (avatarCache) {
            if (out != null) avatarCache.put(wxid0, out);
            else avatarMissing.add(wxid0);
        }
        return out;
    }

    /** 真正干活的解码：先量尺寸再按 2 的幂降采样，别把整张原图读进内存 */
    private android.graphics.Bitmap decodeAvatarFile(File f) {
        try {
            if (f == null || !f.exists() || f.length() < 64) return null;
            int out = dp(96);
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= out && bounds.outHeight / (sample * 2) >= out)
                sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (b == null) return null;
            int size = Math.min(b.getWidth(), b.getHeight());
            android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(b,
                    (b.getWidth() - size) / 2, (b.getHeight() - size) / 2, size, size);
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(sq, out, out, true);
            android.graphics.Bitmap rounded = android.graphics.Bitmap.createBitmap(out, out,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(rounded);
            android.graphics.Paint pt = new android.graphics.Paint();
            pt.setAntiAlias(true);
            android.graphics.RectF rect = new android.graphics.RectF(0, 0, out, out);
            cv.drawRoundRect(rect, dp(16), dp(16), pt);
            pt.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(scaled, 0, 0, pt);
            return rounded;
        } catch (Exception e) {
            return null;
        }
    }

    /** 头像文件变了（下载/删除）→ 丢掉对应缓存，下次渲染重新解码 */
    private void dropAvatarCache(String wxid0) {
        if (wxid0 == null) return;
        synchronized (avatarCache) {
            avatarCache.remove(wxid0);
            avatarMissing.remove(wxid0);
        }
    }

    /** 头像 + 右上角未读红点 */
    private View avatarWithBadge(String name, String key, int unread) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(act);
        View av = avatarView(name, key);
        box.addView(av, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48)));
        if (unread > 0) {
            TextView badge = new TextView(act);
            badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            badge.setTextSize(10);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFFFA5151);
            badge.setBackground(bg);
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(dp(18), dp(18));
            blp.gravity = Gravity.TOP | Gravity.END;
            box.addView(badge, blp);
        }
        return box;
    }

    private String lastTime(String convKey) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (convOf(m).equals(convKey)) return shortTime(m.ts);
        }
        return "";
    }

    private String shortTime(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        java.util.Calendar now = java.util.Calendar.getInstance();
        if (sameDay(c, now)) {
            return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        }
        if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
            return String.format("%d/%d", c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH));
        }
        return String.format("%d/%d/%d", c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String dayLabel(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        java.util.Calendar now = java.util.Calendar.getInstance();
        if (sameDay(c, now)) return "今天";
        java.util.Calendar y = (java.util.Calendar) now.clone();
        y.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(c, y)) return "昨天";
        if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
            return (c.get(java.util.Calendar.MONTH) + 1) + "月"
                    + c.get(java.util.Calendar.DAY_OF_MONTH) + "日";
        }
        return (c.get(java.util.Calendar.YEAR) % 100) + "年"
                + (c.get(java.util.Calendar.MONTH) + 1) + "月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + "日";
    }

    private int unreadOf(String convKey) {
        long read = prefs.getLong("read_" + convKey, 0);
        int n = 0;
        for (Msg m : messages) {
            if (m.id > read && convOf(m).equals(convKey) && !m.from.equals(wxid)) n++;
        }
        return n;
    }

    /** 打开会话即标记已读 */
    private void markRead(String convKey) {
        long max = prefs.getLong("read_" + convKey, 0);
        for (Msg m : messages) {
            if (convOf(m).equals(convKey)) max = Math.max(max, m.id);
        }
        prefs.edit().putLong("read_" + convKey, max).apply();
    }

    // ---------------- 聊天页 ----------------

    private void openChat(String key) {
        pageId = page("chat:" + key, ANIM_FWD);
        openChat = key;
        markRead(key);
        if (twoPane()) {         // 横屏双栏：重绘左右两栏，右侧显示该会话
            showHome();
            return;
        }
        // 手机 / 竖屏平板单栏：直接进聊天页。
        // （竖屏平板以前走 showHome → 只重绘列表，会话点不开）
        reset();
        chatPage = true;   // 返回键 → 回会话列表（而不是退出应用）
        buildChatInto(rootCol, true);
    }

    /** 把聊天界面搭到指定容器里；withBack=false 时（平板右侧）不显示返回按钮 */
    private void buildChatInto(LinearLayout parent, boolean withBack) {
        parent.setPadding(0, 0, 0, 0);

        // 顶栏：扁平一条，标题居中，左边返回、右边「⋯」
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(dark ? 0xFF1C1C1E : 0xFFEDEDED);
        head.setPadding(dp(4), dp(6) + statusBarH, dp(4), dp(6));   // 背景画进状态栏，标题让下来

        if (withBack) {
            TextView back = new TextView(act);
            back.setText("‹");
            back.setTextSize(27);
            back.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
            back.setGravity(Gravity.CENTER);
            back.setPadding(dp(12), 0, dp(12), dp(2));
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingAnim = ANIM_BACK;
                    showHome();
                }
            });
            head.addView(back);
        } else {
            head.addView(new View(act), new LinearLayout.LayoutParams(dp(44), 1));
        }

        chatTitle = new TextView(act);
        chatTitle.setText("聊天");
        chatTitle.setTextSize(17);
        chatTitle.setGravity(Gravity.CENTER);
        chatTitle.setTextColor(dark ? 0xFFF2F2F2 : 0xFF111111);
        head.addView(chatTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(act);
        more.setText("⋯");
        more.setTextSize(22);
        more.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(14), 0, dp(14), dp(6));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatMenu();
            }
        });
        head.addView(more);
        parent.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 群公告横幅（微信那样常驻一条；点它进聊天信息）
        final Group noticeGroup = (openChat != null && openChat.startsWith("g:"))
                ? findGroup(openChat.substring(2)) : null;
        if (noticeGroup != null && noticeGroup.notice != null && !noticeGroup.notice.isEmpty()) {
            TextView noticeBar = new TextView(act);
            noticeBar.setText("📢 " + noticeGroup.notice.replace("\n", " "));
            noticeBar.setTextSize(12);
            noticeBar.setTextColor(dark ? 0xFFE8C87A : 0xFF7A5B00);
            noticeBar.setBackgroundColor(dark ? 0xFF2A2413 : 0xFFFFF6DC);
            noticeBar.setPadding(dp(14), dp(8), dp(14), dp(8));
            noticeBar.setSingleLine(true);
            noticeBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
            noticeBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showGroupInfo(noticeGroup);
                }
            });
            parent.addView(noticeBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 消息区
        chatScroll = new ScrollView(act);
        chatScroll.setBackgroundColor(dark ? 0xFF111113 : 0xFFEDEDED);
        chatList = new LinearLayout(act);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(0, dp(6), 0, dp(10));
        chatScroll.addView(chatList);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        // 不留上下缝：旧版 10dp/6dp 两条缝会露出一条白带，看着像把消息切掉一截
        parent.addView(chatScroll, slp);
        // ⚠️ 绝不在这里把消息区设成 INVISIBLE：V0.2.3 那样做，一旦"显示"的回调没来，
        //    整个聊天记录区就永久白屏（用户实测）。定位靠"画之前同步滚到底"，不靠隐藏。

        // 输入区
        View inputLine = new View(act);
        inputLine.setBackgroundColor(divider());
        parent.addView(inputLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setBackground(glassBar());
        inputRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams irp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final TextView emojiBtn = new TextView(act);  // 表情
        emojiBtn.setText("😀");
        emojiBtn.setTextSize(19);
        emojiBtn.setGravity(Gravity.CENTER);
        emojiBtn.setPadding(dp(2), 0, dp(4), 0);
        inputRow.addView(emojiBtn, new LinearLayout.LayoutParams(dp(40), dp(46)));

        TextView attachBtn = new TextView(act);       // 附件：拍照 / 相册 / 文件
        attachBtn.setText("📎");
        attachBtn.setTextSize(20);
        attachBtn.setGravity(Gravity.CENTER);
        attachBtn.setPadding(0, 0, dp(4), 0);
        attachBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogAttach();
            }
        });
        inputRow.addView(attachBtn, new LinearLayout.LayoutParams(dp(42), dp(46)));

        final EditText et = new EditText(act);
        et.setHint("说点什么…");
        et.setTextColor(dark ? 0xFFF2F5FA : 0xFF16203A);
        et.setHintTextColor(0xFF9AA4B2);
        et.setBackground(null);   // 交给系统主题（Material 下划线输入框）
        et.setPadding(dp(8), dp(10), dp(8), dp(10));
        et.setMaxLines(4);
        // 点输入框（键盘弹出）→ 把最新消息顶到输入栏上方，别让刚发的话被键盘挡住
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) return;
                final Runnable pin = new Runnable() {
                    @Override
                    public void run() {
                        pinChatToBottom(3);
                    }
                };
                et.postDelayed(pin, 120);
                et.postDelayed(pin, 400);   // 键盘弹出/收起有动画，结束时再补一次
            }
        });
        inputRow.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = new TextView(act);
        send.setText("➤");
        send.setTextSize(18);
        send.setTextColor(Color.WHITE);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(accent);
        send.setBackground(sendBg);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(dp(46), dp(46));
        slp2.leftMargin = dp(10);
        inputRow.addView(send, slp2);
        parent.addView(inputRow, irp);

        // 表情面板：点 😀 展开/收起
        final LinearLayout emojiRow = new LinearLayout(act);
        emojiRow.setOrientation(LinearLayout.VERTICAL);
        emojiRow.setVisibility(View.GONE);
        emojiRow.setPadding(0, dp(6), 0, 0);
        String[] emojis = {"😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
                "🤔", "😅", "😭", "😡", "😴", "🙄", "😇", "🥳",
                "👍", "👏", "🙏", "💪", "👌", "✌️", "🤝", "🎉",
                "❤️", "🔥", "✨", "🌹", "🍀", "☕", "🍜", "🎂",
                "🐶", "🐱", "🌧", "☀️", "🌙", "⭐", "🚀", "💡"};
        LinearLayout line0 = null;
        for (int i = 0; i < emojis.length; i++) {
            if (i % 8 == 0) {
                line0 = new LinearLayout(act);
                line0.setOrientation(LinearLayout.HORIZONTAL);
                emojiRow.addView(line0);
            }
            TextView e = new TextView(act);
            e.setText(emojis[i]);
            e.setTextSize(21);
            e.setGravity(Gravity.CENTER);
            final String em = emojis[i];
            e.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    et.getText().insert(et.getSelectionStart(), em);
                }
            });
            line0.addView(e, new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        parent.addView(emojiRow);
        emojiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                emojiRow.setVisibility(emojiRow.getVisibility() == View.GONE
                        ? View.VISIBLE : View.GONE);
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = et.getText().toString().trim();
                if (text.isEmpty()) return;
                et.setText("");
                sendMessage(text);
            }
        });

        updateChatTitle();
        chatFirstRender = true;   // 首帧直接停在底部，不从顶上"跳"下来
        renderedConv = null;      // 新页面 = 空列表，必须整表重建
        renderedSig = "";
        renderedCount = 0;
        renderedLastTs = 0;
        renderMessages();
        // 兜底：万一消息区被任何路径设成了不可见（历史遗留/未来改动），0.6 秒后强制显示。
        // 只修可见性、不碰滚动位置 —— 用户这会儿要是往上翻历史，不会被拽回底部。
        if (chatScroll != null) {
            chatScroll.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (chatScroll != null && chatScroll.getVisibility() != View.VISIBLE)
                        chatScroll.setVisibility(View.VISIBLE);
                }
            }, 600);
        }
    }

    private void updateChatTitle() {
        if (openChat == null || chatTitle == null) return;
        String[] p = openChat.split(":", 2);
        if ("u".equals(p[0])) {
            Friend f = findFriend(p[1]);
            chatTitle.setText(f != null ? f.nickname : p[1]);
        } else {
            Group g = findGroup(p[1]);
            chatTitle.setText(g != null ? g.name + "（" + g.members.size() + "）" : "群聊");
        }
    }

    /** 我的二维码：给别人扫，扫到就能加我 */
    private void showMyQr() {
        android.graphics.Bitmap qr = QrUtil.make(QrUtil.addLink(wxid), 720);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(20), dp(24), dp(16));
        if (qr != null) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setImageBitmap(qr);
            box.addView(iv, new LinearLayout.LayoutParams(dp(240), dp(240)));
        }
        TextView t = new TextView(act);
        t.setText((nickname == null || nickname.isEmpty() ? wxid : nickname)
                + "\n专属号：" + wxid + "\n\n让对方打开「发现 → 扫一扫」");
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, 0);
        box.addView(t);
        new AlertDialog.Builder(act).setTitle("我的二维码").setView(box)
                .setPositiveButton("好", null).show();
    }

    /** 扫到二维码 → 找这个人 → 走加好友确认 */
    private void handleScan(String text) {
        if (text != null && text.startsWith("linglum://gjoin?")) {
            joinGroupByQr(text);
            return;
        }
        final String target = QrUtil.parseWxid(text);
        if (target == null) {
            toast("这不是灵珑聊天的加好友二维码");
            return;
        }
        if (target.equals(wxid)) {
            toast("这是你自己的二维码");
            return;
        }
        if (findFriend(target) != null) {
            toast("「" + target + "」已经是你的好友了");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String pk = fetchPubkey(target);
                final String nick = nicknameOfUser(target);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pk == null) {
                            toast("找不到这个人（可能已注销）");
                        } else {
                            confirmAdd(target, nick == null ? target : nick, pk);
                        }
                    }
                });
            }
        }).start();
    }

    /** 顺带取一下对方昵称（失败就返回 null） */
    private String nicknameOfUser(String id) {
        try {
            JSONObject r = api.get("/api/pubkeys?ids=" + id);
            JSONObject us = r.optJSONObject("users");
            JSONObject u = us == null ? null : us.optJSONObject(id);
            return u == null ? null : u.optString("nickname", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 平板切换页签：只重建左栏，聊天区原地不动 */
    private void refreshWideTabs() {
        if (wideLeftHost == null) {
            showTab(currentTab);
            return;
        }
        for (int i = 0; i < railItemViews.size(); i++) {
            int c = (i == currentTab) ? accent : textSub();
            railItemViews.get(i)[0].setTextColor(c);
            railItemViews.get(i)[1].setTextColor(c);
        }
        wideLeftHost.removeAllViews();
        if (currentTab == 1) buildContactsInto(wideLeftHost);
        else if (currentTab == 2) buildDiscoverInto(wideLeftHost);
        else if (currentTab == 3) buildMeInto(wideLeftHost);
        else buildSidebar(wideLeftHost);
    }

    /** 长按消息：复制 / 转发 / 撤回（2 分钟内、自己的）/ 删除 */
    private void msgMenu(final Msg m) {
        final boolean mine = m.from.equals(wxid);
        java.util.List<String> items = new ArrayList<String>();
        if (m.plain != null && m.attach == null) items.add("复制");
        if (m.plain != null) items.add("转发");
        if (mine && !m.recalled && System.currentTimeMillis() - m.ts <= 120000) items.add("撤回");
        items.add("删除");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(act)
                .setItems(arr, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String pick = arr[which];
                        if ("复制".equals(pick)) {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.plain));
                            toast("已复制");
                        } else if ("转发".equals(pick)) {
                            forwardMsg(m);
                        } else if ("撤回".equals(pick)) {
                            doRecall(m);
                        } else {
                            messages.remove(m);
                            rewriteMessages();
                            renderMessages();
                            toast("已删除");
                        }
                    }
                })
                .show();
    }

    private void doRecall(final Msg m) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject r = api.post("/api/messages/" + m.id + "/recall", new JSONObject());
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "撤回失败"));
                    }
                    m.recalled = true;
                    rewriteMessages();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderMessages();
                            toast("已撤回");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("撤回失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 转发：选一个会话，把文字再发一次 */
    private void forwardMsg(final Msg m) {
        if (m.plain == null) return;
        final String text = m.plain;
        final java.util.List<String> names = new ArrayList<String>();
        final java.util.List<String> keys = new ArrayList<String>();
        for (Friend f : friends) {
            names.add("👤 " + f.nickname);
            keys.add("u:" + f.wxid);
        }
        for (Group g : groups) {
            names.add("👥 " + g.name);
            keys.add("g:" + g.gid);
        }
        if (names.isEmpty()) {
            toast("还没有可以转发的会话");
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("转发给…")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        sendMessageTo(keys.get(which), text, null);
                        toast("已转发");
                    }
                })
                .show();
    }

    private void renderMessages() {
        if (chatList == null || openChat == null) return;
        // 重建前先判断：首帧 / 原本贴着底部 → 跟到底；正在翻历史 → 保持不动
        final boolean jumpBottom = chatFirstRender || isNearBottom();
        java.util.List<Msg> snapshot;
        synchronized (messages) {
            snapshot = new java.util.ArrayList<Msg>(messages);
        }
        String conv = openChat;
        // 当前会话的消息 + 前缀签名（id:撤回标记）——用来判断能不能"只追加"
        java.util.List<Msg> list = new java.util.ArrayList<Msg>();
        StringBuilder sig = new StringBuilder();
        for (Msg m : snapshot) {
            if (!convOf(m).equals(conv)) continue;
            list.add(m);
            sig.append(m.id).append(':').append(m.recalled ? 1 : 0).append('|');
        }
        String fullSig = sig.toString();
        boolean appendOnly = conv.equals(renderedConv) && renderedSig.length() > 0
                && fullSig.length() > renderedSig.length() && fullSig.startsWith(renderedSig)
                && renderedCount > 0 && renderedCount <= list.size();
        if (!appendOnly) {
            chatList.removeAllViews();
            renderedLastTs = 0;
            renderedCount = 0;
        }
        // ⚠️ 只在末尾追加新消息：整表 removeAllViews() 会让 ScrollView 的滚动位置瞬间归零，
        //    每来一条新消息就"从头滑到底"——用户看到的就是这个。只有会话切换/撤回/删除才整表重建。
        for (int i = renderedCount; i < list.size(); i++) {
            renderedLastTs = appendMsgRow(list.get(i), renderedLastTs);
        }
        renderedConv = conv;
        renderedSig = fullSig;
        renderedCount = list.size();
        if (jumpBottom) {
            // 首帧多校正几帧（页面刚挂上去时宽度/换行可能与离屏测量不同，内容高度会变一截）
            pinChatToBottom(chatFirstRender ? 6 : 3);
        }
        chatFirstRender = false;
    }

    /** 渲染单条消息，返回这条消息的时间（给下一条判断时间分隔线用） */
    private long appendMsgRow(Msg m, long lastTs) {
            // 微信那样：只有隔了 5 分钟以上才插一条时间
            if (lastTs == 0 || m.ts - lastTs > 5 * 60 * 1000L) {
                TextView sep = new TextView(act);
                sep.setText(shortTime(m.ts));
                sep.setTextSize(11);
                sep.setTextColor(0xFF9A9A9A);
                sep.setGravity(Gravity.CENTER);
                sep.setPadding(0, dp(10), 0, dp(6));
                chatList.addView(sep);
            }
            lastTs = m.ts;

            final boolean mine = m.from.equals(wxid);
            final boolean inGroup = "group".equals(m.toType);

            if (m.recalled) {
                TextView sys = new TextView(act);
                sys.setText((mine ? "你" : nameOf(m.from)) + " 撤回了一条消息");
                sys.setTextSize(12);
                sys.setTextColor(0xFF9A9A9A);
                sys.setGravity(Gravity.CENTER);
                sys.setPadding(0, dp(4), 0, dp(6));
                chatList.addView(sys);
                return m.ts;
            }

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(3), 0, dp(3));

            View avatar = avatarView(mine ? nickname : nameOf(m.from),
                    mine ? "me" : "u:" + m.from);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(38), dp(38));
            if (mine) alp.leftMargin = dp(6);
            else alp.rightMargin = dp(6);

            final Msg cur0 = m;
            TextView body = new TextView(act);
            if (m.attach != null) {
                body.setText(attachLabel(m));
                body.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toast("正在下载…");
                        openAttachment(cur0);
                    }
                });
            } else {
                body.setText(m.plain != null ? m.plain
                        : "🔒 无法解密（换密钥前的旧消息，新消息正常）");
            }
            body.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    msgMenu(cur0);
                    return true;
                }
            });
            body.setTextSize(16);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            if (mine) {
                bg.setColor(dark ? 0xFF1F4D33 : 0xFFD7F5DF);
                body.setTextColor(dark ? 0xFFDDF5E6 : 0xFF0E2416);
            } else {
                bg.setColor(cardBg());
                body.setTextColor(textMain());
            }
            body.setBackground(bg);
            body.setPadding(dp(12), dp(9), dp(12), dp(9));
            body.setMaxWidth((int) (act.getResources().getDisplayMetrics().widthPixels * 0.62));

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(3);
            if (mine) blp.rightMargin = dp(6);
            else blp.leftMargin = dp(6);

            // 群聊里别人的消息：气泡上方显示发送者昵称（微信就是这样，不然分不清谁说的）
            View bubble = body;
            if (inGroup && !mine) {
                LinearLayout col = new LinearLayout(act);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView who = new TextView(act);
                who.setText(nameOf(m.from));
                who.setTextSize(11);
                who.setTextColor(dark ? 0xFF9A9AA0 : 0xFF8A8A8E);
                who.setPadding(dp(4), 0, 0, dp(2));
                col.addView(who);
                col.addView(body);
                bubble = col;
            }

            View spacer = new View(act);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, 1, 1f);
            if (mine) {
                row.addView(spacer, slp2);
                row.addView(bubble, blp);
                row.addView(avatar, alp);
            } else {
                row.addView(avatar, alp);
                row.addView(bubble, blp);
                row.addView(spacer, slp2);
            }
            chatList.addView(row);
        return m.ts;
    }

    /** 当前聊天是否已经贴着底部（48dp 以内都算贴着） */
    private boolean isNearBottom() {
        if (chatScroll == null) return true;
        View child = chatScroll.getChildAt(0);
        if (child == null) return true;
        int max = child.getHeight() - chatScroll.getHeight();
        return max <= 0 || chatScroll.getScrollY() >= max - dp(48);
    }

    /** 是否真的贴着底部（4dp 误差内） */
    private boolean isAtBottom() {
        if (chatScroll == null) return true;
        View child = chatScroll.getChildAt(0);
        if (child == null) return true;
        int max = child.getHeight() - chatScroll.getHeight();
        return max <= 0 || chatScroll.getScrollY() >= max - dp(4);
    }

    /** 同步把消息区滚到底（ScrollView 自己会把超大 y 钳到真实的最大滚动量） */
    private void scrollChatNow() {
        if (chatScroll == null || chatList == null) return;
        if (chatScroll.getVisibility() != View.VISIBLE) chatScroll.setVisibility(View.VISIBLE);
        chatScroll.scrollTo(0, Integer.MAX_VALUE >>> 2);
    }

    /**
     * 把消息区钉到底部：不隐藏、不跳帧，画这一帧之前把滚动位置设好。
     * ⚠️ 用 OnPreDrawListener 是为了"这一帧画出来就是底部"（动画回调在 layout 之前，算不到新高度）；
     *    但 preDraw 只在真的绘制时回调 —— 页面安静下来就不再回调 → 监听器会一直挂着，
     *    用户之后往上翻历史会被它拽回底部。所以额外挂一个 300ms 的强制摘除，绝不残留。
     */
    private void pinChatToBottom(final int frames) {
        final ScrollView sv = chatScroll;
        if (sv == null || chatList == null) return;
        scrollChatNow();                               // 现在就有高度的话立刻生效（多半已经见效）
        final int[] left = {Math.max(1, frames)};
        final int[] calm = {0};
        final boolean[] done = {false};
        final android.view.ViewTreeObserver.OnPreDrawListener[] self =
                new android.view.ViewTreeObserver.OnPreDrawListener[1];
        self[0] = new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                if (done[0] || sv != chatScroll || left[0]-- <= 0) {
                    done[0] = true;
                    if (vto.isAlive()) vto.removeOnPreDrawListener(self[0]);
                    return true;
                }
                scrollChatNow();
                if (isAtBottom()) {
                    if (++calm[0] >= 2) {              // 连续两帧都贴底 = 布局稳定，收工
                        done[0] = true;
                        if (vto.isAlive()) vto.removeOnPreDrawListener(self[0]);
                    }
                } else {
                    calm[0] = 0;                       // 内容又长高了 → 继续校正
                }
                return true;                           // 已滚到位，正常画这一帧（不跳帧，避免闪）
            }
        };
        sv.getViewTreeObserver().addOnPreDrawListener(self[0]);
        // 兜底摘除：没有后续绘制时 preDraw 不会再来，但监听器必须走，否则会一直拽着滚动位置
        sv.postDelayed(new Runnable() {
            @Override
            public void run() {
                done[0] = true;
                android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnPreDrawListener(self[0]);
            }
        }, 300);
    }

    /** 聊天页「⋯」：刷新 / 清空本地聊天记录 */
    private void chatMenu() {
        if (openChat == null) return;
        final String conv = openChat;
        // 微信式：群聊点 ⋯ 直接进「聊天信息」页（群管理在那一页里）；单聊还是弹菜单
        if (conv.startsWith("g:")) {
            Group g = findGroup(conv.substring(2));
            if (g == null) {
                toast("群信息还没同步回来，稍后再试");
                return;
            }
            showGroupInfo(g);
            return;
        }
        new AlertDialog.Builder(act)
                .setItems(new String[]{"刷新消息", "清空聊天记录", "删除好友"},
                        new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    pollOnce(pollGen);
                                }
                            }).start();
                            toast("正在刷新…");
                        } else if (which == 1) {
                            confirmClearChat();
                        } else {
                            String id = conv.substring(2);
                            Friend f = findFriend(id);
                            if (f == null) {          // 好友列表还没同步回来也别拦着
                                f = new Friend();
                                f.wxid = id;
                                f.nickname = id;
                            }
                            dialogDeleteFriend(f);
                        }
                    }
                })
                .show();
    }

    /** 退出群聊：先确认（退出后本机这个群的记录也会清掉） */
    private void dialogLeaveGroup(final Group g) {
        new AlertDialog.Builder(act)
                .setTitle("退出群聊")
                .setMessage("退出后不再接收「" + g.name + "」的消息，本机这个群的聊天记录也会清空。")
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        leaveGroup(g);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真正退群：服务端摘成员 + 删我这份群密钥 → 本地移出群列表、清掉本群记录 → 回会话列表 */
    private void leaveGroup(final Group g) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    JSONObject r = api.delete("/api/groups/" + g.gid + "/members/me");
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "服务器退出失败"));
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                groups.remove(g);
                saveGroups();
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Msg m = messages.get(i);
                    if ("group".equals(m.toType) && g.gid.equals(m.toId)) messages.remove(i);
                }
                rewriteMessages();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr != null) {
                            toast("已在本地退出，但服务器没退掉：" + ferr);
                        } else {
                            toast("已退出群聊");
                        }
                        if (openChat != null && openChat.equals("g:" + g.gid)) openChat = null;
                        showTab(currentTab);
                    }
                });
            }
        }).start();
    }

    // ---------------- 群聊「聊天信息」/「群管理」 ----------------

    private boolean groupListPage = false;  // 当前停在「群聊」列表页（通讯录入口进来）
    private boolean newFriendsPage = false; // 当前停在「新的朋友」页（好友申请）
    private Group groupInfoPage = null;    // 当前停在「聊天信息」页的群（返回键用）
    private Group groupAdminPage = null;   // 当前停在「群管理」页的群
    private boolean loginPage = false;     // 当前停在登录页（返回键=退出应用）
    private boolean momentsPage = false;   // 当前停在朋友圈
    private boolean composePage = false;   // 当前停在发表朋友圈
    private boolean chatPage = false;      // 当前停在手机单栏聊天页
    private Runnable pickBack = null;      // 选人页（发起群聊/邀请成员）的返回动作

    /**
     * 逐级返回：每个整页在自己的 show 方法里登记「上一页是谁」，
     * 返回键严格按登记回退，绝不在中间页直接 finish 退出。
     * （此前只有群聊三个子页有登记，聊天/朋友圈/选人/主 tab 全部跨级退出 —— 用户实测反馈。）
     */
    public void back() {
        pendingAnim = ANIM_BACK;              // 逐级返回 → 从左滑入（与页内 ‹ 同手感）
        if (loginPage) {                      // 登录页没有上一页 → 退出应用
            pendingAnim = ANIM_NONE;
            act.finish();
            return;
        }
        if (pickBack != null) {               // 选人页 → 它自己的上一页（邀请→群信息 / 建群→列表）
            Runnable r = pickBack;
            pickBack = null;
            r.run();
            return;
        }
        if (composePage) {                    // 发表朋友圈 → 朋友圈
            showMoments();
            return;
        }
        if (momentsPage) {                    // 朋友圈 → 发现页签
            showTab(2);
            return;
        }
        if (newFriendsPage) {                 // 新的朋友 → 通讯录
            newFriendsPage = false;
            showTab(1);
            return;
        }
        if (groupListPage) {                  // 群聊列表 → 通讯录
            groupListPage = false;
            showTab(1);
            return;
        }
        if (groupAdminPage != null) {         // 群管理 → 聊天信息
            Group g = groupAdminPage;
            groupAdminPage = null;
            showGroupInfo(g);
            return;
        }
        if (groupInfoPage != null) {          // 聊天信息 → 回到会话
            Group g = groupInfoPage;
            groupInfoPage = null;
            openChat("g:" + g.gid);
            return;
        }
        if (chatPage || (tablet && openChat != null)) {  // 聊天页 → 会话列表（与页内 ‹ 按钮同路径）
            if (tablet) {                     // 平板双栏：只关掉右侧聊天，不换页
                openChat = null;
                showTab(currentTab);
            } else {
                showHome();
            }
            return;
        }
        if (currentTab != 0) {                // 非首页页签 → 先回「微信」页签（与微信一致）
            showTab(0);
            return;
        }
        pendingAnim = ANIM_NONE;              // 退出应用，别把动画留给下一次
        act.finish();                         // 首页 → 退出应用
    }

    private boolean isGroupOwner(Group g) {
        return g != null && wxid != null && wxid.equals(g.owner);
    }

    /** 群信息指纹：成员/管理员/群名/公告/权限里有任何变化，才需要重绘页面 */
    private String groupSig(Group g) {
        if (g == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(g.gid).append('|').append(g.name).append('|').append(g.owner)
                .append('|').append(g.nameAdminOnly).append('|').append(g.allowMemberInvite)
                .append('|').append(g.notice).append('|');
        for (String m : g.members) sb.append(m).append(',');
        sb.append('|');
        for (String a : g.admins) sb.append(a).append(',');
        return sb.toString();
    }

    private String groupsSig() {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) sb.append(groupSig(g)).append(';');
        return sb.toString();
    }

    private boolean isGroupAdmin(Group g) {
        return isGroupOwner(g) || (g != null && g.admins.contains(wxid));
    }

    /** 群成员显示名：自己 → 昵称；好友 → 昵称；都不是 → 服务端下发的昵称；最后兜底专属号 */
    private String memberName(Group g, String id) {
        if (id == null) return "";
        if (id.equals(wxid)) return (nickname == null || nickname.isEmpty()) ? wxid : nickname;
        Friend f = findFriend(id);
        if (f != null && f.nickname != null && !f.nickname.isEmpty()) return f.nickname;
        if (g != null) {
            String n = g.memberNames.get(id);
            if (n != null && !n.isEmpty()) return n;
        }
        return id;
    }

    /** 子页面顶栏：‹ + 居中标题 */
    private View subPageBar(final String title, final Runnable onBack) {
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(dark ? 0xFF1C1C1E : 0xFFEDEDED);
        head.setPadding(dp(4), dp(6) + statusBarH, dp(4), dp(6));

        TextView back = new TextView(act);
        back.setText("‹");
        back.setTextSize(27);
        back.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(6), 0, dp(10), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingAnim = ANIM_BACK;   // 子页 ‹ → 与返回键同手感（从左滑入）
                onBack.run();
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textMain());
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(new View(act), new LinearLayout.LayoutParams(dp(44), dp(1)));
        return head;
    }

    /** 群聊右上角 ⋯ → 聊天信息页 */
    private void showGroupInfo(final Group g) {
        pageId = page("ginfo:" + g.gid, ANIM_FWD);
        groupInfoPage = g;
        groupAdminPage = null;
        reset();
        chatList = null;          // 这一页没有聊天列表，别让轮询往废弃的旧视图里塞消息
        chatScroll = null;
        rootCol.addView(subPageBar("聊天信息(" + g.members.size() + ")", new Runnable() {
            @Override
            public void run() {
                groupInfoPage = null;
                openChat("g:" + g.gid);
            }
        }));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(18));
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(buildMemberGrid(g));

        list.addView(listRow("🔳", 0xFF4C6FFF, "群二维码", "扫码即可加入本群", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGroupQr(g);
            }
        }));
        list.addView(listRow("📢", 0xFFE8890C, "群公告",
                g.notice.isEmpty() ? "未设置" : g.notice.replace("\n", " "),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialogNotice(g);
                    }
                }));
        list.addView(listRow("✏️", 0xFF12B886, "群聊名称", g.name, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogRenameGroup(g);
            }
        }));
        if (isGroupAdmin(g)) {   // 群主 + 群管理员都能进；能做什么由群管理页再分
            list.addView(listRow("🛡", 0xFF4C6FFF, "群管理",
                    isGroupOwner(g) ? "管理员 · 群主转让 · 解散群聊" : "群公告 · 群设置",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showGroupAdmin(g);
                        }
                    }));
        }
        list.addView(listRow("🧹", 0xFF8A94A6, "清空聊天记录", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearChat("g:" + g.gid);
            }
        }));
        list.addView(redRow("退出群聊", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogLeaveGroup(g);
            }
        }));

        // 打开这一页时和服务端对齐一次：别人退群/改名/改公告，这里要马上能看到（否则会显示过期成员）
        final String sig0 = groupSig(g);
        refreshGroupsQuiet(new Runnable() {
            @Override
            public void run() {
                if (groupInfoPage != g) return;              // 已经离开这一页
                Group fresh = findGroup(g.gid);
                if (fresh == null) {                         // 群已解散 / 自己已被移出
                    toast("这个群已经不存在了");
                    groupInfoPage = null;
                    showTab(currentTab);
                    return;
                }
                if (!groupSig(fresh).equals(sig0)) showGroupInfo(fresh);   // 有变化才重绘
            }
        });
    }

    /** 群成员网格（微信那样一行 5 个，自己排第一） */
    private View buildMemberGrid(final Group g) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(6), dp(8), dp(6), dp(8));
        wrap.setBackground(cardBgDrawable(16));
        java.util.List<String> ms = new ArrayList<String>(g.members);
        if (ms.remove(wxid)) ms.add(0, wxid);
        final boolean canInvite = isGroupAdmin(g) || g.allowMemberInvite == 1;
        final int total = ms.size() + (canInvite ? 1 : 0);
        final int per = 5;
        LinearLayout row = null;
        for (int i = 0; i < ms.size(); i++) {
            if (i % per == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                wrap.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String mid = ms.get(i);
            String nm = memberName(g, mid);
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(6), 0, dp(6));
            cell.addView(avatarView(nm, "u:" + mid),
                    new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView t = new TextView(act);
            t.setText(nm);
            t.setTextSize(11);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(textSub());
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            cell.addView(t, tlp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Friend f = findFriend(mid);
                    if (f != null) showProfileDialog(f);
                    else toast(memberName(g, mid) + "（专属号 " + mid + "）");
                }
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (canInvite) {            // 「＋」邀请新成员（微信的成员网格右上角那个加号）
            if (row == null || ms.size() % per == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                wrap.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(6), 0, dp(6));
            TextView plus = new TextView(act);
            plus.setText("＋");
            plus.setTextSize(22);
            plus.setGravity(Gravity.CENTER);
            plus.setTextColor(textSub());
            GradientDrawable pbg = new GradientDrawable();
            pbg.setCornerRadius(dp(12));
            pbg.setStroke(dp(1), dark ? 0xFF3A3A3C : 0xFFD9D9DE);
            plus.setBackground(pbg);
            cell.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView pt = new TextView(act);
            pt.setText("邀请");
            pt.setTextSize(11);
            pt.setGravity(Gravity.CENTER);
            pt.setTextColor(textSub());
            LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp2.topMargin = dp(4);
            cell.addView(pt, tlp2);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickFriends("邀请成员", g.members, new Runnable() {
                        @Override
                        public void run() {
                            showGroupInfo(g);
                        }
                    }, new PickCallback() {
                        @Override
                        public void onPicked(List<Friend> picked) {
                            inviteMembers(g, picked);
                        }
                    });
                }
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row != null) {          // 补齐空格，保证每列等宽
            for (int i = total % per; i != 0 && i < per; i++) {
                row.addView(new View(act), new LinearLayout.LayoutParams(0, dp(1), 1f));
            }
        }
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(10);
        wlp.rightMargin = dp(10);
        wrap.setLayoutParams(wlp);
        return wrap;
    }

    /** 整行红字按钮（退出群聊 / 解散该群聊） */
    private View redRow(String text, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFE03131);
        t.setPadding(0, dp(16), 0, dp(16));
        t.setBackground(cardBgDrawable(16));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 带开关的行 */
    private View switchRow(String title, boolean checked,
                           final android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch sw = new android.widget.Switch(act);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setBackground(cardBgDrawable(16));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(10);
        olp.rightMargin = dp(10);
        olp.topMargin = dp(3);
        olp.bottomMargin = dp(3);
        row.setLayoutParams(olp);
        return row;
    }

    /** 群管理页（只有群主能进） */
    private void showGroupAdmin(final Group g) {
        if (!isGroupAdmin(g)) {
            toast("只有群主或管理员可以使用群管理");
            return;
        }
        final boolean owner = isGroupOwner(g);
        groupAdminPage = g;
        groupInfoPage = null;
        pageId = page("gadmin:" + g.gid, ANIM_FWD);
        reset();
        chatList = null;
        chatScroll = null;
        rootCol.addView(subPageBar("群管理", new Runnable() {
            @Override
            public void run() {
                groupAdminPage = null;
                showGroupInfo(g);
            }
        }));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(18));
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(switchRow("仅群主/群管理员可修改群聊名称", g.nameAdminOnly == 1,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        saveGroupSettings(g, checked ? 1 : 0, g.allowMemberInvite, b,
                                checked ? "已开启：仅群主/管理员可改名" : "已关闭：任何成员可改名");
                    }
                }));
        list.addView(switchRow("允许普通成员邀请新成员", g.allowMemberInvite == 1,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        saveGroupSettings(g, g.nameAdminOnly, checked ? 1 : 0, b,
                                checked ? "已开启：成员可以拉人进群" : "已关闭：只有群主/管理员能拉人");
                    }
                }));
        // 群主专属：转让 / 设管理员 / 解散（管理员能看到页面，但这三项没有权限）
        if (owner) {
            list.addView(listRow("👑", 0xFFE8890C, "群主管理权转让", null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialogTransferOwner(g);
                }
            }));
            list.addView(listRow("🛡", 0xFF4C6FFF, "群管理员",
                    g.admins.isEmpty() ? "未设置" : (g.admins.size() + " 人"),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialogSetAdmins(g);
                        }
                    }));
            list.addView(redRow("解散该群聊", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialogDissolveGroup(g);
                }
            }));
        } else {
            list.addView(listRow("👑", 0xFF8A94A6, "群主", memberName(g, g.owner), null));
            list.addView(listRow("🛡", 0xFF8A94A6, "群管理员",
                    g.admins.isEmpty() ? "未设置" : (g.admins.size() + " 人"), null));
        }
    }

    /** 保存群设置（开关） */
    private void saveGroupSettings(final Group g, final int nameAdminOnly, final int allowInvite,
                                   final android.widget.CompoundButton sw, final String okMsg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("name_admin_only", nameAdminOnly == 1);
                    b.put("allow_member_invite", allowInvite == 1);
                    JSONObject r = api.post("/api/groups/" + g.gid + "/settings", b);
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "保存失败"));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                if (ferr == null) {
                    g.nameAdminOnly = nameAdminOnly;
                    g.allowMemberInvite = allowInvite;
                    saveGroups();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr != null) {
                            toast("保存失败：" + ferr);
                            sw.setChecked(!sw.isChecked());   // 还原开关
                        } else {
                            toast(okMsg);
                        }
                    }
                });
            }
        }).start();
    }

    /** 改群名（受「仅群主/管理员可改」限制） */
    private void dialogRenameGroup(final Group g) {
        if (g.nameAdminOnly == 1 && !isGroupAdmin(g)) {
            toast("只有群主或管理员可以修改群名称");
            return;
        }
        final EditText et = new EditText(act);
        et.setText(g.name);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(act)
                .setTitle("修改群聊名称")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            toast("群名称不能为空");
                            return;
                        }
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("name", name);
                                    JSONObject r = api.post("/api/groups/" + g.gid + "/name", b);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "改名失败"));
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String ferr = err;
                                if (ferr == null) {
                                    g.name = name;
                                    saveGroups();
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (ferr != null) {
                                            toast("改名失败：" + ferr);
                                        } else {
                                            toast("群名称已修改");
                                            showGroupInfo(g);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 群公告：成员都能看；群主/管理员可编辑（微信里也是这个权限） */
    private void dialogNotice(final Group g) {
        if (!isGroupAdmin(g)) {
            new AlertDialog.Builder(act)
                    .setTitle("群公告")
                    .setMessage(g.notice.isEmpty() ? "还没有群公告" : g.notice)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final EditText et = new EditText(act);
        et.setText(g.notice);
        et.setHint("写点什么给全群看…（可留空 = 清空公告）");
        et.setMinLines(4);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        new AlertDialog.Builder(act)
                .setTitle("群公告")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String text = et.getText().toString().trim();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("notice", text);
                                    JSONObject r = api.post("/api/groups/" + g.gid + "/notice", b);
                                    if (r.optInt("_http", 0) >= 400) {
                                        throw new Exception(r.optString("error", "保存失败"));
                                    }
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String ferr = err;
                                if (ferr == null) {
                                    g.notice = text;
                                    saveGroups();
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (ferr != null) {
                                            toast("保存失败：" + ferr);
                                        } else {
                                            toast(text.isEmpty() ? "群公告已清空" : "群公告已更新");
                                            showGroupInfo(g);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 群主管理权转让 */
    private void dialogTransferOwner(final Group g) {
        final java.util.List<String> others = new ArrayList<String>();
        final java.util.List<String> names = new ArrayList<String>();
        for (String m : g.members) {
            if (m.equals(wxid)) continue;
            others.add(m);
            names.add(memberName(g, m));
        }
        if (others.isEmpty()) {
            toast("群里还没有其他成员");
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("转让群主给…")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        final String to = others.get(which);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("member", to);
                                    JSONObject r = api.post("/api/groups/" + g.gid + "/owner", b);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "转让失败"));
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String ferr = err;
                                if (ferr == null) {
                                    g.owner = to;
                                    saveGroups();
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (ferr != null) {
                                            toast("转让失败：" + ferr);
                                        } else {
                                            toast("已把群主转给 " + memberName(g, to));
                                            showGroupInfo(g);        // 自己不再是群主，群管理入口会消失
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置群管理员（多选，最多 10 人） */
    private void dialogSetAdmins(final Group g) {
        final java.util.List<String> cands = new ArrayList<String>();
        final java.util.List<String> names = new ArrayList<String>();
        for (String m : g.members) {
            if (m.equals(g.owner)) continue;
            cands.add(m);
            names.add(memberName(g, m));
        }
        if (cands.isEmpty()) {
            toast("群里还没有其他成员");
            return;
        }
        final boolean[] sel = new boolean[cands.size()];
        for (int i = 0; i < cands.size(); i++) sel[i] = g.admins.contains(cands.get(i));
        new AlertDialog.Builder(act)
                .setTitle("群管理员（可多选）")
                .setMultiChoiceItems(names.toArray(new String[0]), sel,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                                sel[which] = isChecked;
                            }
                        })
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final JSONArray arr = new JSONArray();
                        for (int i = 0; i < cands.size(); i++) if (sel[i]) arr.put(cands.get(i));
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("members", arr);
                                    JSONObject r = api.post("/api/groups/" + g.gid + "/admins", b);
                                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "保存失败"));
                                    JSONArray back = r.optJSONArray("admins");
                                    g.admins.clear();
                                    if (back != null) for (int i = 0; i < back.length(); i++) g.admins.add(back.getString(i));
                                    saveGroups();
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String ferr = err;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (ferr != null) {
                                            toast("保存失败：" + ferr);
                                        } else {
                                            toast("管理员已更新");
                                            showGroupAdmin(g);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 解散群聊：二次确认 */
    private void dialogDissolveGroup(final Group g) {
        new AlertDialog.Builder(act)
                .setTitle("解散该群聊")
                .setMessage("解散后所有成员都会被移出，聊天记录不再同步，且不可恢复。确定要解散「"
                        + g.name + "」吗？")
                .setPositiveButton("解散", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        dissolveGroup(g);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dissolveGroup(final Group g) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    JSONObject r = api.delete("/api/groups/" + g.gid);
                    if (r.optInt("_http", 0) >= 400) throw new Exception(r.optString("error", "解散失败"));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                if (ferr == null) {
                    groups.remove(g);
                    saveGroups();
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        Msg m = messages.get(i);
                        if ("group".equals(m.toType) && g.gid.equals(m.toId)) messages.remove(i);
                    }
                    rewriteMessages();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr != null) {
                            toast("解散失败：" + ferr);
                            return;
                        }
                        toast("群聊已解散");
                        openChat = null;
                        groupInfoPage = null;
                        groupAdminPage = null;
                        showTab(currentTab);
                    }
                });
            }
        }).start();
    }

    private void confirmClearChat() {
        if (openChat == null) return;
        confirmClearChat(openChat);
    }

    /** 清空指定会话的本机记录（聊天页和「聊天信息」页都用它） */
    private void confirmClearChat(final String conv) {
        if (conv == null) return;
        new AlertDialog.Builder(act)
                .setTitle("清空聊天记录")
                .setMessage("只清空本机这个会话的记录，不可恢复。")
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            if (convOf(messages.get(i)).equals(conv)) messages.remove(i);
                        }
                        rewriteMessages();
                        if (chatList != null) renderMessages();
                        toast("已清空");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String attachLabel(Msg m) {
        try {
            JSONObject a = new JSONObject(m.attach);
            if ("img".equals(a.optString("kind"))) return "🖼 图片（点击查看）";
            long sz = a.optLong("size", 0);
            return "📎 " + a.optString("name", "文件") + "（" + (sz / 1024) + "KB）";
        } catch (Exception e) {
            return "📎 附件";
        }
    }

    private String nameOf(String id) {
        if (id.equals(wxid)) return nickname;
        Friend f = findFriend(id);
        return f != null ? f.nickname : id;
    }

    private void sendMessage(final String text) {
        if (openChat == null) return;
        sendMessageTo(openChat, text, null);
    }

    private void sendMessage(final String text, final String attachJson) {
        if (openChat == null) return;
        sendMessageTo(openChat, text, attachJson);
    }

    /** 发到指定会话（转发也用它） */
    private void sendMessageTo(final String conv, final String text, final String attachJson) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] p = conv.split(":", 2);
                    String toType = "u".equals(p[0]) ? "user" : "group";
                    String toId = p[1];
                    SecretKey k;
                    if ("user".equals(toType)) {
                        Friend f = findFriend(toId);
                        if (f == null) throw new Exception("找不到联系人");
                        // v0.0.6：发送前向服务端对齐一次最新公钥 —— 对方找回账号换过密钥也发得出去
                        String pk = refreshPeerPub(toId);
                        if (pk == null || pk.isEmpty()) pk = f.pubkey;
                        k = Crypto.sharedKey(myPriv, pk);
                    } else {
                        Group gr = findGroup(toId);
                        if (gr == null || gr.key == null) throw new Exception("群密钥缺失");
                        k = gr.key;
                    }
                    // 防篡改：把发件人/收件/时间/序号/前一条哈希/内容一起签名，再连成消息链
                    long ts = System.currentTimeMillis();
                    String chainKey = "chain_" + conv + "_" + wxid;
                    String[] cs = prefs.getString(chainKey, "0|GENESIS").split("\\|", 2);
                    int seq = Integer.parseInt(cs[0]) + 1;
                    String prev = cs.length > 1 ? cs[1] : "GENESIS";
                    // 双签名：sig = 老格式（旧版 App 还能验，升级过渡期不报"被篡改"）
                    //         s2  = v2（附件/收件/序号全绑死，新版只认这个）
                    String canon = Crypto.canonical(wxid, toType, toId, ts, seq, prev, text);
                    final String sigB64 = Crypto.sign(myPriv, canon);
                    String attachSigStr = attachJson == null ? "" : Crypto.attachSig(attachJson);
                    String sig2 = Crypto.sign(myPriv, Crypto.canonicalV2(wxid, toType, toId,
                            ts, seq, prev, text, attachSigStr));

                    org.json.JSONObject env = new org.json.JSONObject();
                    env.put("sv", Crypto.SIG_V2);        // 签名方案版本，收方据此选严格校验
                    env.put("s2", sig2);
                    env.put("t", text);
                    env.put("f", wxid);
                    env.put("tt", toType);
                    env.put("ti", toId);
                    env.put("ts", ts);
                    env.put("s", seq);
                    env.put("p", prev);
                    if (attachJson != null) env.put("a", new org.json.JSONObject(attachJson));

                    JSONObject body = new JSONObject();
                    body.put("to_type", toType);
                    body.put("to_id", toId);
                    body.put("payload", Crypto.encrypt(k, env.toString()));
                    body.put("sig", sigB64);
                    body.put("ts", ts);
                    JSONObject r = api.post("/api/messages", body);
                    if (r.optInt("_http", 0) == 401 || r.optInt("_http", 0) == 403) {
                        handleSessionEnd("登录已失效，请重新登录");
                        return;
                    }
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "发送失败"));
                    }
                    // 立刻把自己发的消息贴到聊天框（不用等下一次轮询）
                    final Msg mine = new Msg();
                    mine.id = r.optLong("id", 0);
                    mine.from = wxid;
                    mine.toType = toType;
                    mine.toId = toId;
                    mine.payload = body.optString("payload");
                    mine.sig = sigB64;
                    mine.seq = seq;
                    mine.prev = prev;
                    mine.verified = 1;
                    mine.ts = ts;
                    mine.plain = text;
                    // 更新本地消息链状态
                    prefs.edit().putString(chainKey, seq + "|" + Crypto.sha256Hex(sigB64)).apply();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            messages.add(mine);
                            saveMessage(mine);
                            renderMessages();
                            toast("已发送");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("发送失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- 轮询 ----------------

    private void startPolling() {
        final int gen = ++pollGen;   // 作废上一代线程（它可能正卡在长轮询里）
        polling = true;
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                while (polling && gen == pollGen) {
                    if (upgradeRequired) {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            break;
                        }
                        continue;
                    }
                    try {
                        pollOnce(gen);       // 长轮询：服务端有新消息立刻返回
                    } catch (Throwable t) {
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastKeySync > 120000L) {     // 每 2 分钟：对齐好友公钥 + 刷新群密钥
                        lastKeySync = now;
                        refreshAllFriendKeys();
                        refreshGroups();     // 别人替我补发的群密钥在这里拿到
                    }
                    try {
                        Thread.sleep(200);   // 短暂歇一下再挂下一次
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        pollThread.start();
    }

    private void pollOnce(final int gen) {
        if (gen != pollGen) return;          // 上一代的线程，结果一律不要
        try {
            final long sinceStart = cursor;
            JSONObject r = api.getLong("/api/messages?since=" + cursor + "&wait=25");
            if (gen != pollGen) return;      // 这期间重新登录过 → 旧 token 的结果作废
            if (r.optInt("_http", 0) == 426) {
                upgradeRequired = true;      // 暂停后续请求，避免 426 风暴把手机拖卡
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        forceUpdate();
                    }
                });
                return;
            }
            int code = r.optInt("_http", 0);
            if (code == 401 || code == 403) {     // token 失效 / 账号已被删除
                handleSessionEnd("account_deleted".equals(r.optString("error"))
                        ? "账号在服务端已不存在，请重新注册"
                        : "登录已失效，请重新登录");
                return;
            }
            if (code >= 400) return;
            JSONArray arr = r.optJSONArray("messages");
            if (arr == null || arr.length() == 0) {
                if (restoringHistory) {          // 恢复中收到空批次 = 已追平
                    restoringHistory = false;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderMessages();
                            if (openChat == null) showHome();
                            toast("聊天记录已恢复（" + restoreCount + " 条）");
                        }
                    });
                }
                return;
            }
            // 大 backlog：从 0 开始 / 一批拉满 500 条 → 进入（或继续）恢复模式
            if (sinceStart == 0 && arr.length() >= 20) {
                restoringHistory = true;
                restoreCount = 0;
            }
            final boolean restoreEnds = restoringHistory && arr.length() < 500;
            for (int i = 0; i < arr.length(); i++) {
                if (gen != pollGen) return;   // 处理途中退出/重登 → 立即停手
                JSONObject o = arr.getJSONObject(i);
                Msg m = new Msg();
                m.id = o.optLong("id");
                m.from = o.optString("from");
                m.toType = o.optString("to_type");
                m.toId = o.optString("to_id");
                m.payload = o.optString("payload");
                m.sig = o.optString("sig");
                m.ts = o.optLong("ts");
                m.recalled = o.optInt("recalled", 0) == 1;
                decrypt(m);
                if (m.plain == null) {
                    // 解不开的两种可能：① 还没加好友没有公钥 ② 对方找回账号换过密钥
                    // → 一律去服务器要「最新」公钥，更新本地后重解一次
                    String peer = m.from.equals(wxid) ? m.toId : m.from;
                    String fresh = refreshPeerPub(peer);
                    if (fresh == null) fresh = ensurePeerPub(peer);
                    if (fresh != null) decrypt(m);
                }
                if (m.plain == null) {
                    syncLog.append("解密失败 id=").append(m.id).append(" from=").append(m.from).append('\n');
                }
                // 去重：自己刚发的那条已经本地先显示过了
                boolean exists = false;
                for (Msg x : messages) {
                    if (x.id == m.id && m.id != 0) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    messages.add(m);
                    saveMessage(m);
                    if (restoringHistory) restoreCount++;
                }
                cursor = Math.max(cursor, m.id);
            }
            long saved = prefs.getLong("cursor", 0);
            if (cursor < saved) cursor = saved;   // 后台服务可能已经推进过
            prefs.edit().putLong("cursor", cursor).apply();
            syncInfo = "同步 " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " · since=" + sinceStart + " · 收到 " + arr.length() + " 条 · 游标 " + cursor
                    + " · 共 " + messages.size() + " 条";
            syncLog.append(syncInfo).append('\n');
            if (gen != pollGen) return;
            final int got = arr.length();
            final boolean wasRestoring = restoringHistory;
            if (restoreEnds) restoringHistory = false;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (syncView != null) syncView.setText(syncInfo);
                    if (restoringHistory) {
                        updateRestoreTip();      // 恢复中：只更新顶部进度，不整页重建
                        return;
                    }
                    renderMessages();
                    if (openChat == null) showHome();
                    if (wasRestoring && gen == pollGen) {
                        toast("聊天记录已恢复（" + restoreCount + " 条）");
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 解密 + 防篡改校验：
     *  ① 密文内嵌的发件人必须等于服务器给的发件人（服务器偷换 from 会被抓）
     *  ② ECDSA 签名必须对得上（改内容/改收件/改时间/改序号都会失败）
     *  ③ 消息链：seq 必须接续、prev 必须等于上一条的哈希（删消息/重排/重放会被抓）
     *
     * v0.0.6：一条消息会依次试「对方当前公钥 + 历史公钥」——
     * 对方找回账号换过密钥时，新消息用新公钥解、旧消息用旧公钥解，互不影响。
     */
    /** 每条消息都做一次 ECDH 太慢（500 条恢复要好几秒）—— 按公钥缓存共享密钥 */
    private javax.crypto.SecretKey peerSharedKey(String pubB64) throws Exception {
        javax.crypto.SecretKey k = peerKeyCache.get(pubB64);
        if (k != null) return k;
        k = Crypto.sharedKey(myPriv, pubB64);
        if (peerKeyCache.size() > 400) peerKeyCache.clear();
        peerKeyCache.put(pubB64, k);
        return k;
    }

    private void decrypt(Msg m) {
        m.plain = null;
        m.verified = 0;
        if (myPriv == null) return;
        if ("group".equals(m.toType)) {
            Group gr = findGroup(m.toId);
            if (gr == null || gr.key == null) return;
            List<String> cands = candidatePubs(m.from);
            if (cands.isEmpty() && !m.from.equals(wxid)) {
                // 群友不是好友时本地没有他的公钥 → 向服务端要一次，拿到就能验签（不再裸奔）
                if (refreshPeerPub(m.from) != null) cands = candidatePubs(m.from);
            }
            if (cands.isEmpty()) cands.add(null);       // 实在拿不到 → 只解密不验签
            Msg best = null;
            for (String pp : cands) {
                Msg r = decryptTry(m, gr.key, pp);
                if (r == null) continue;
                if (r.verified == 1) { adopt(m, r); return; }
                if (best == null) best = r;
            }
            if (best != null) adopt(m, best);
            return;
        }
        String peer = m.from.equals(wxid) ? m.toId : m.from;
        Msg best = null;
        for (String pp : candidatePubs(peer)) {
            SecretKey k;
            try {
                k = peerSharedKey(pp);
            } catch (Exception e) {
                continue;
            }
            Msg r = decryptTry(m, k, pp);
            if (r == null) continue;
            if (r.verified == 1) { adopt(m, r); return; }
            if (best == null) best = r;
        }
        if (best != null) adopt(m, best);
    }

    private void adopt(Msg dst, Msg src) {
        dst.plain = src.plain;
        dst.verified = src.verified;
        dst.attach = src.attach;
        dst.seq = src.seq;
        dst.prev = src.prev;
    }

    /** 用指定密钥解一条消息；解不开返回 null，解开就返回填好的副本（含验签/消息链结果） */
    private Msg decryptTry(Msg src, SecretKey k, String peerPub) {
        Msg m = new Msg();
        m.id = src.id;
        m.from = src.from;
        m.toType = src.toType;
        m.toId = src.toId;
        m.payload = src.payload;
        m.sig = src.sig;
        m.ts = src.ts;
        try {
            String raw = Crypto.decrypt(k, src.payload);
            // 旧版本消息（没有信封）直接显示，标记为未校验
            if (!raw.startsWith("{")) {
                m.plain = raw;
                m.verified = 0;
                return m;
            }

            org.json.JSONObject env = new org.json.JSONObject(raw);
            String text = env.optString("t");
            String from = env.optString("f");
            long ts = env.optLong("ts");
            int seq = env.optInt("s");
            String prev = env.optString("p");
            String attach = env.optString("a", "");
            int sv = env.optInt("sv", 1);                       // 签名方案：2=附件也签
            String tt = env.optString("tt", src.toType);
            String ti = env.optString("ti", src.toId);
            m.attach = attach.isEmpty() ? null : attach;
            m.seq = seq;
            m.prev = prev;

            if (!from.equals(src.from)) {
                m.plain = text + "\n⚠️ 发件人与签名不符（可能被篡改）";
                m.verified = -1;
                return m;
            }
            // 信封里的收件信息必须和服务端那一行一致（服务端把消息改成"发给别人"会被抓）
            if (!tt.equals(src.toType) || !ti.equals(src.toId)) {
                m.plain = text + "\n⚠️ 收件信息与签名不符（可能被篡改）";
                m.verified = -1;
                return m;
            }

            boolean mine = from.equals(wxid);
            // ⚠️ 自己发的消息用「我的公钥」验签。之前 here 拿的是对方公钥 → 必挂，
            //    重启后所有自己发过的消息都会被误标成"签名校验失败"。
            String vpub = mine ? pubkeyB64 : peerPub;
            if (vpub == null || src.sig == null || src.sig.isEmpty()) {
                m.plain = text;
                m.verified = 0;      // 没有公钥或旧消息，无法校验
                return m;
            }

            // sv>=2：只认信封里的 v2 签名 s2；缺失或验不过一律判失败（绝不降级回老格式）
            String sigUse = src.sig;
            if (sv >= Crypto.SIG_V2) {
                sigUse = env.optString("s2", "");
                if (sigUse.isEmpty()) {
                    syncLog.append("v2 消息缺 s2 签名 id=").append(src.id).append('\n');
                    m.plain = text + "\n⚠️ 签名缺失（消息可能被篡改）";
                    m.verified = -1;
                    return m;
                }
            }
            if (!Crypto.verifyMessage(vpub, sigUse, from, tt, ti, ts, seq, prev,
                    text, attach, sv)) {
                syncLog.append("验签失败 id=").append(src.id)
                        .append(" from=").append(from)
                        .append(" ts=").append(ts).append(" seq=").append(seq)
                        .append(" sv=").append(sv)
                        .append(" sig=").append(src.sig.length()).append('\n');
                if (mine) {
                    // 自己换过密钥之前发出的消息，用现在的公钥验不过属正常 → 不告警
                    m.plain = text;
                    m.verified = 0;
                    return m;
                }
                m.plain = text + "\n⚠️ 签名校验失败（消息可能被篡改）";
                m.verified = -1;
                return m;
            }

            // 自己发的消息：链状态在发送时就记过了，不重复检查
            if (!mine) {
                String chainKey = "chain_" + convOf(m) + "_" + from;
                String[] cs = prefs.getString(chainKey, "0|GENESIS").split("\\|", 2);
                int lastSeq = Integer.parseInt(cs[0]);
                String lastHash = cs.length > 1 ? cs[1] : "GENESIS";
                if (rebuildingHistory) {
                    // 重建期：只推进不报警
                    if (seq > lastSeq) {
                        prefs.edit().putString(chainKey,
                                seq + "|" + Crypto.sha256Hex(src.sig)).apply();
                    }
                    m.plain = text;
                    m.verified = 1;
                    return m;
                }
                boolean fresh = (lastSeq == 0 && "GENESIS".equals(lastHash));
                boolean gap = false;
                if (fresh) {
                    // 本机第一次见这个会话（新手机 / 换过密钥）→ 接上当前序号，不误报断链
                    syncLog.append("消息链起点 id=").append(src.id)
                            .append(" seq=").append(seq).append('\n');
                } else if (seq <= lastSeq) {
                    // 重连/重启/重同步都会把老消息再解一遍 → 这是正常现象，不是重放
                    // （以前这里会标"消息序号重复"，结果历史消息一片黄 ⚠️）
                    m.plain = text;
                    m.verified = 1;
                    return m;
                } else if (seq != lastSeq + 1 || !prev.equals(lastHash)) {
                    // 中间有消息没收全/解不开：只提示这一条，链继续往前走，
                    // 不再让「漏一条」把后面所有消息永久标成断链
                    gap = true;
                    syncLog.append("消息链缺口 id=").append(src.id)
                            .append(" seq=").append(seq).append(" last=").append(lastSeq)
                            .append('\n');
                }
                prefs.edit().putString(chainKey,
                        seq + "|" + Crypto.sha256Hex(src.sig)).apply();
                if (gap) {
                    m.plain = text + "\n⚠️ 前面有消息缺失（可能被删除或没收到）";
                    m.verified = -1;
                    return m;
                }
            }

            m.plain = text;
            m.verified = 1;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private String pubkeyOf(String id) {
        if (id.equals(wxid)) return pubkeyB64;
        Friend f = findFriend(id);
        return f != null ? f.pubkey : null;
    }

    // ---------------- 服务器：群组 ----------------

    private void refreshGroups() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    applyGroupsJson(api.get("/api/groups"));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (openChat == null) showHome();
                            updateChatTitle();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** 只把群资料拉新（不切页面），完成后回 UI 线程；给「打开聊天信息/群聊列表时对齐一次」用 */
    private void refreshGroupsQuiet(final Runnable done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    applyGroupsJson(api.get("/api/groups"));
                } catch (Exception ignored) {
                }
                if (done != null) ui.post(done);
            }
        }).start();
    }

    /** 解析 /api/groups：成员 / 管理员 / 群名 / 公告 全量覆盖进本地缓存（并被 saveGroups 落盘） */
    private void applyGroupsJson(JSONObject r) throws Exception {
        JSONArray arr = r.optJSONArray("groups");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Group g = new Group();
            g.gid = o.getString("gid");
            g.name = o.getString("name");
            g.owner = o.getString("owner");
            g.myWrapped = o.optString("my_wrapped", "");
            g.wrappedBy = o.optString("my_wrapped_by", "");
            JSONArray mem = o.optJSONArray("members");
            g.members.clear();
            if (mem != null) {
                for (int j = 0; j < mem.length(); j++) g.members.add(mem.getString(j));
            }
            JSONArray adm = o.optJSONArray("admins");
            g.admins.clear();
            if (adm != null) {
                for (int j = 0; j < adm.length(); j++) g.admins.add(adm.getString(j));
            }
            g.nameAdminOnly = o.optInt("name_admin_only", 0);
            g.notice = o.optString("notice", "");
            g.allowMemberInvite = o.optInt("allow_member_invite", 1);
            // 群成员不一定是好友，本地通讯录里没有他们的昵称 —— 服务端随群列表一起下发
            JSONObject profs = r.optJSONObject("profiles");
            if (profs != null) {
                for (int j = 0; j < g.members.size(); j++) {
                    String mid = g.members.get(j);
                    JSONObject pf = profs.optJSONObject(mid);
                    if (pf == null) continue;
                    String nn = pf.optString("nickname", "");
                    if (!nn.isEmpty()) g.memberNames.put(mid, nn);
                }
            }
            if (!g.myWrapped.isEmpty()) unwrapGroupKey(g);
            upsertGroup(g);
            syncMemberPubs(g);      // 群消息验签需要成员公钥（不是好友也能拿到）
        }
        saveGroups();
    }

    private void unwrapGroupKey(Group g) {
        if ("qr".equals(g.wrappedBy)) {          // 扫码入群：密钥是本地用二维码里的口令解开的
            String saved = prefs.getString("gkey_" + g.gid, "");
            if (!saved.isEmpty()) {
                try {
                    g.key = new javax.crypto.spec.SecretKeySpec(
                            android.util.Base64.decode(saved, android.util.Base64.NO_WRAP), "AES");
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (g.myWrapped == null || g.myWrapped.isEmpty()) return;
        // v0.0.6：包裹者可能是群主，也可能是后来帮你补发密钥的其他成员；
        // 而且包裹者自己换过密钥时，用最新公钥才解得开 → 逐个候选试。
        String wrapper = (g.wrappedBy != null && !g.wrappedBy.isEmpty()) ? g.wrappedBy : g.owner;
        List<String> pubs = new ArrayList<String>();
        if (wrapper.equals(wxid)) {
            pubs.add(pubkeyB64);
        } else {
            String fresh = refreshPeerPub(wrapper);      // 先问服务器要最新公钥
            if (fresh != null) pubs.add(fresh);
            for (String s0 : candidatePubs(wrapper)) if (!pubs.contains(s0)) pubs.add(s0);
        }
        for (String wp : pubs) {
            try {
                g.key = Crypto.unwrapKey(myPriv, wp, g.myWrapped);
                return;
            } catch (Exception ignored) {
            }
        }
        g.key = null;
        syncLog.append("群密钥也解不开 ").append(g.gid).append("（包裹者 ")
                .append(wrapper).append("，会等对方上线后自动补发）\n");
    }

    /** 拉群成员当前公钥（群消息验签用；不是好友也能从服务端拿到） */
    private void syncMemberPubs(Group g) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String w : g.members) {
                if (w.equals(wxid) || findFriend(w) != null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(w);
            }
            if (sb.length() == 0) return;
            JSONObject r = api.get("/api/pubkeys?ids=" + sb);
            JSONObject us = r.optJSONObject("users");
            if (us == null) return;
            java.util.Iterator<String> it = us.keys();
            boolean changed = false;
            while (it.hasNext()) {
                String w = it.next();
                JSONObject u = us.optJSONObject(w);
                if (u == null) continue;
                String pk = u.optString("pubkey", "");
                if (!pk.isEmpty() && !pk.equals(peerPubCache.get(w))) {
                    peerPubCache.put(w, pk);
                    changed = true;
                }
            }
            if (changed) savePeerPub();
        } catch (Exception ignored) {
        }
    }

    private String fetchPubkey(String id) {
        try {
            JSONObject r = api.get("/api/search?q=" + id);
            JSONArray arr = r.optJSONArray("users");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.getJSONObject(i);
                    if (u.getString("wxid").equals(id)) return u.getString("pubkey");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void upsertGroup(Group g) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).gid.equals(g.gid)) {
                groups.set(i, g);
                return;
            }
        }
        groups.add(g);
    }

    // ---------------- 对话框 ----------------

    private void dialogAddFriend() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText et = new EditText(act);
        et.setHint("输入对方的专属号");
        box.addView(et);
        final TextView tip = new TextView(act);
        tip.setTextSize(13);
        tip.setTextColor(0xFF8A94A6);
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(act)
                .setTitle("添加好友")
                .setView(box)
                .setPositiveButton("查找并添加", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String q = et.getText().toString().trim();
                        if (q.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    JSONObject r = api.get("/api/search?q=" + q);
                                    JSONArray arr = r.optJSONArray("users");
                                    if (arr == null || arr.length() == 0) {
                                        ui.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast("没找到这个专属号");
                                            }
                                        });
                                        return;
                                    }
                                    final JSONObject u = arr.getJSONObject(0);
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                confirmAdd(u.getString("wxid"),
                                                        u.optString("nickname"),
                                                        u.getString("pubkey"));
                                            } catch (Exception e) {
                                                toast("添加失败");
                                            }
                                        }
                                    });
                                } catch (Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("查找失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 加好友确认：显示安全码；如果已经是好友就直接明确提示（不再静默） */
    private void confirmAdd(final String id, final String nick, final String pub) {
        if (findFriend(id) != null) {
            toast("「" + nick + "」已经是你的好友了，在会话列表里");
            return;
        }
        String fp = Crypto.fingerprint(pub);
        new AlertDialog.Builder(act)
                .setTitle("添加好友：" + nick)
                .setMessage("专属号：" + id + "\n安全码：" + fp
                        + "\n\n（可选）当面核对安全码，防止中间人")
                .setPositiveButton("发送申请", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        sendFriendRequest(id, nick);     // v0.1.4：对方通过后才是好友
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点未添加的人：查公钥 → 走确认添加 */
    private void addStranger(final String id) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String pk = fetchPubkey(id);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pk == null) {
                            toast("找不到这个用户（可能已注销）");
                            return;
                        }
                        confirmAdd(id, id, pk);
                    }
                });
            }
        }).start();
    }

    // ---------------- 新的朋友：好友申请（v0.1.4）----------------

    /** 新的朋友页：按专属号添加 + 待处理申请 + 我发出的申请 */
    private void showNewFriends() {
        reset();
        newFriendsPage = true;      // 返回键 → 通讯录
        rootCol.addView(subPageBar("新的朋友", new Runnable() {
            @Override
            public void run() {
                showTab(1);
            }
        }));
        ScrollView sv = new ScrollView(act);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.addView(listRow("🔍", 0xFF3B7BE0, "按专属号添加", "输入对方的玲珑号，发送好友申请",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialogAddFriend();
                    }
                }));
        loadFriendRequests(list);
    }

    /** 拉好友申请并渲染（保留第 0 行"按专属号添加"） */
    private void loadFriendRequests(final LinearLayout list) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONArray fin = null, fout = null;
                try {
                    JSONObject r = api.get("/api/friends/requests");
                    fin = r.optJSONArray("incoming");
                    fout = r.optJSONArray("outgoing");
                } catch (Exception ignored) {
                }
                final JSONArray fin2 = fin, fout2 = fout;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        while (list.getChildCount() > 1) list.removeViewAt(1);
                        addReqSection(list, "待处理", fin2, true);
                        addReqSection(list, "我发出的", fout2, false);
                        int n = (fin2 == null ? 0 : fin2.length()) + (fout2 == null ? 0 : fout2.length());
                        if (n == 0) {
                            TextView tip = new TextView(act);
                            tip.setText("还没有好友申请\n加好友要对方通过后，双方才会出现在通讯录里");
                            tip.setTextSize(13);
                            tip.setTextColor(textSub());
                            tip.setPadding(dp(16), dp(20), dp(16), dp(20));
                            list.addView(tip);
                        }
                    }
                });
            }
        }).start();
    }

    private void addReqSection(LinearLayout list, String title, JSONArray arr, boolean incoming) {
        if (arr == null || arr.length() == 0) return;
        TextView sec = new TextView(act);
        sec.setText(title + "（" + arr.length() + "）");
        sec.setTextSize(12);
        sec.setTextColor(textSub());
        sec.setPadding(dp(16), dp(12), dp(16), dp(6));
        list.addView(sec);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String wx = o.optString("wxid");
            String nick = o.optString("nickname", wx);
            list.addView(requestRow(wx, nick, incoming, list));
        }
    }

    /** 一条好友申请：接受 / 拒绝（我发出的只显示"等待通过"） */
    private View requestRow(final String wx, final String nick, final boolean incoming,
                            final LinearLayout list) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(act);
        t1.setText(nick);
        t1.setTextSize(15.5f);
        t1.setTextColor(textMain());
        TextView t2 = new TextView(act);
        t2.setText(wx);
        t2.setTextSize(12);
        t2.setTextColor(textSub());
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (incoming) {
            TextView acc = new TextView(act);
            acc.setText("接受");
            acc.setTextSize(14);
            acc.setTextColor(0xFFFFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(accent);
            acc.setBackground(bg);
            acc.setPadding(dp(14), dp(6), dp(14), dp(6));
            acc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answerFriend(wx, true, list);
                }
            });
            row.addView(acc);

            TextView rej = new TextView(act);
            rej.setText("拒绝");
            rej.setTextSize(14);
            rej.setTextColor(textSub());
            rej.setPadding(dp(12), dp(6), dp(6), dp(6));
            rej.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answerFriend(wx, false, list);
                }
            });
            row.addView(rej);
        } else {
            TextView wait = new TextView(act);
            wait.setText("等待通过");
            wait.setTextSize(12.5f);
            wait.setTextColor(textSub());
            row.addView(wait);
        }
        return row;
    }

    /** 接受 / 拒绝好友申请 */
    private void answerFriend(final String wx, final boolean accept, final LinearLayout list) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean okDone = false;
                try {
                    JSONObject b = new JSONObject();
                    b.put("wxid", wx);
                    api.post("/api/friends/" + (accept ? "accept" : "reject"), b);
                    okDone = true;
                } catch (Exception ignored) {
                }
                final boolean okFinal = okDone;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!okFinal) {
                            toast("操作失败，检查网络后再试");
                            return;
                        }
                        toast(accept ? "✅ 已添加为好友" : "已拒绝");
                        if (accept) refreshFriendProfiles();
                        loadFriendRequests(list);
                    }
                });
            }
        }).start();
    }

    /** 发好友申请：服务端只写"我这一行"，对方通过才成为好友 */
    private void sendFriendRequest(final String id, final String nick) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String state = "";
                String err = "";
                try {
                    JSONObject b = new JSONObject();
                    b.put("wxid", id);
                    JSONObject r = api.post("/api/friends", b);
                    state = r.optString("state", "");
                } catch (Exception e) {
                    err = e.getMessage() == null ? "" : e.getMessage();
                }
                final String st = state, er = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if ("friends".equals(st)) {
                            refreshFriendProfiles();
                            showHome();
                            toast("✅ 对方之前也申请过你，现在已经是好友了");
                        } else if ("pending".equals(st)) {
                            toast("已发送好友申请，等「" + nick + "」通过");
                            showNewFriends();
                        } else {
                            toast("申请没发出去" + (er.isEmpty() ? "，检查网络" : "：" + er));
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------- 群聊：选人页 / 建群 / 邀请 ----------------

    /** 发起群聊：微信那样先选人（搜索 + 多选），点「完成」建群并直接进群 */
    private void dialogCreateGroup() {
        if (friends.isEmpty()) {
            toast("先添加好友才能建群");
            return;
        }
        pickFriends("发起群聊", null, new Runnable() {
            @Override
            public void run() {
                showTab(currentTab);
            }
        }, new PickCallback() {
            @Override
            public void onPicked(List<Friend> picked) {
                createGroupFromPicker(picked);
            }
        });
    }

    /** 选好友的页面（建群、邀请入群共用）：搜索 + 多选 + 完成 */
    private interface PickCallback {
        void onPicked(List<Friend> picked);
    }

    private void pickFriends(final String title, final List<String> exclude,
                             final Runnable onBack, final PickCallback cb) {
        pageId = page("pick:" + title, ANIM_FWD);
        reset();
        pickBack = onBack;   // 返回键 = 页内 ‹ 按钮（邀请→群信息 / 建群→列表）
        chatList = null;
        chatScroll = null;
        rootCol.addView(subPageBar(title, onBack));

        final java.util.LinkedHashSet<String> chosen = new java.util.LinkedHashSet<String>();
        final List<String> ex = exclude == null ? new ArrayList<String>() : exclude;

        final TextView done = new TextView(act);
        done.setText("完成");
        done.setTextSize(16);
        done.setTextColor(Color.WHITE);
        done.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(accent);
        done.setBackground(bg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        dlp.leftMargin = dp(16);
        dlp.rightMargin = dp(16);
        dlp.topMargin = dp(8);
        dlp.bottomMargin = dp(10);

        final EditText search = input("搜索好友", "");
        search.setTextSize(14);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16); slp.rightMargin = dp(16); slp.bottomMargin = dp(6);
        rootCol.addView(search, slp);

        ScrollView sv = new ScrollView(act);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootCol.addView(done, dlp);

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                String kw = search.getText().toString().trim().toLowerCase();
                int shown = 0;
                for (final Friend f : friends) {
                    if (ex.contains(f.wxid)) continue;                 // 已经在群里的人不再列
                    if (!kw.isEmpty() && !f.nickname.toLowerCase().contains(kw)
                            && !f.wxid.toLowerCase().contains(kw)) continue;
                    shown++;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(14), dp(7), dp(14), dp(7));
                    row.addView(avatarView(f.nickname, "u:" + f.wxid),
                            new LinearLayout.LayoutParams(dp(40), dp(40)));
                    TextView nm = new TextView(act);
                    nm.setText(f.nickname);
                    nm.setTextSize(16);
                    nm.setTextColor(dark ? 0xFFEDEDED : 0xFF191919);
                    nm.setPadding(dp(12), 0, 0, 0);
                    row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    final CheckBox cb = new CheckBox(act);
                    cb.setChecked(chosen.contains(f.wxid));
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (chosen.contains(f.wxid)) chosen.remove(f.wxid);
                            else chosen.add(f.wxid);
                            cb.setChecked(chosen.contains(f.wxid));
                            done.setText(chosen.isEmpty() ? "完成" : "完成（" + chosen.size() + "）");
                        }
                    });
                    list.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
                }
                if (shown == 0) {
                    TextView tip = new TextView(act);
                    tip.setText("没有可选的好友");
                    tip.setTextSize(14);
                    tip.setTextColor(dark ? 0xFF6E6E73 : 0xFFB2B2B2);
                    tip.setGravity(Gravity.CENTER);
                    tip.setPadding(0, dp(40), 0, 0);
                    list.addView(tip);
                }
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { render[0].run(); }
        });
        render[0].run();

        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chosen.isEmpty()) {
                    toast("至少选一个好友");
                    return;
                }
                List<Friend> picked = new ArrayList<Friend>();
                for (String id : chosen) {
                    Friend f = findFriend(id);
                    if (f != null) picked.add(f);
                }
                cb.onPicked(picked);
            }
        });
    }

    /** 选完人 → 建群（默认群名取前几个人的昵称，微信也是这样） */
    private void createGroupFromPicker(List<Friend> picked) {
        StringBuilder nm = new StringBuilder();
        for (int i = 0; i < picked.size() && i < 3; i++) {
            if (i > 0) nm.append("、");
            nm.append(picked.get(i).nickname);
        }
        if (picked.size() > 3) nm.append("…");
        final String name = nm.length() == 0 ? "群聊" : nm.toString();
        // 防手滑：成员完全一样的群已经存在时先问一句
        for (Group eg : groups) {
            if (eg.members.size() != picked.size() + 1 || !eg.members.contains(wxid)) continue;
            boolean same = true;
            for (Friend pf : picked) {
                if (!eg.members.contains(pf.wxid)) { same = false; break; }
            }
            if (!same) continue;
            new AlertDialog.Builder(act)
                    .setTitle("已经有一样的群了")
                    .setMessage("你和这些好友已经在一个群「" + eg.name + "」里了。还要再建一个吗？")
                    .setPositiveButton("仍然新建", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d2, int w2) {
                            createGroupNow(name, picked);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        createGroupNow(name, picked);
    }

    /** 真正建群：生成群密钥 → 给每个成员包裹一份 → POST /api/groups → 建完直接进群 */
    private void createGroupNow(final String name, final List<Friend> picked) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecretKey gk = Crypto.newGroupKey();
                    JSONObject members = new JSONObject();
                    members.put(wxid, Crypto.wrapKeyForMember(gk, myPriv, pubkeyB64));   // 自己那份
                    for (Friend f : picked) {
                        members.put(f.wxid, Crypto.wrapKeyForMember(gk, myPriv, f.pubkey));
                    }
                    JSONObject body = new JSONObject();
                    body.put("name", name.isEmpty() ? "群聊" : name);
                    body.put("members", members);
                    JSONObject r = api.post("/api/groups", body);
                    if (r.optInt("_http", 0) >= 400) {
                        throw new Exception(r.optString("error", "创建失败"));
                    }
                    final String gid = r.optString("gid", "");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("群创建成功");
                            refreshGroupsQuiet(new Runnable() {
                                @Override
                                public void run() {
                                    if (!gid.isEmpty()) openChat("g:" + gid);
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("创建失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 邀请好友进群：用群密钥给每个人包裹一份，再调服务端加人 */
    private void inviteMembers(final Group g, List<Friend> picked) {
        if (picked.isEmpty()) return;
        if (g.key == null) {
            toast("群密钥还没解出来，稍后再试");
            return;
        }
        final List<Friend> who = new ArrayList<Friend>(picked);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int okN = 0, failN = 0;
                String lastErr = null;
                for (Friend f : who) {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("wxid", f.wxid);
                        b.put("wrapped", Crypto.wrapKeyForMember(g.key, myPriv, f.pubkey));
                        JSONObject r = api.post("/api/groups/" + g.gid + "/members", b);
                        if (r.optInt("_http", 0) >= 400) {
                            throw new Exception(r.optString("error", "邀请失败"));
                        }
                        okN++;
                    } catch (Exception e) {
                        failN++;
                        lastErr = e.getMessage();
                    }
                }
                final int ok0 = okN, fail0 = failN;
                final String err0 = lastErr;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fail0 == 0) toast("已邀请 " + ok0 + " 人");
                        else if (ok0 == 0) toast("邀请失败：" + err0);
                        else toast("成功 " + ok0 + " 人，失败 " + fail0 + " 人：" + err0);
                        refreshGroupsQuiet(new Runnable() {
                            @Override
                            public void run() {
                                Group fresh = findGroup(g.gid);
                                if (fresh != null && groupInfoPage != null) showGroupInfo(fresh);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    /** 启动后台收消息服务（前台服务，退到后台也能收到并通知） */
    private void startChatService() {
        if (!prefs.getBoolean("bg_service", true)) return;   // 用户关了后台接收
        try {
            Intent i = new Intent(act, ChatService.class);
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
            else act.startService(i);
        } catch (Exception ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 201);
        }
    }

    /** 取对方公钥：好友 → 缓存 → 服务端查一次 */
    private String ensurePeerPub(String peer) {
        if (peer.equals(wxid)) return pubkeyB64;
        Friend f = findFriend(peer);
        if (f != null) return f.pubkey;
        String cached = peerPubCache.get(peer);
        if (cached != null) return cached;
        try {
            JSONObject r = api.get("/api/search?q=" + peer);
            JSONArray arr = r.optJSONArray("users");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.getJSONObject(i);
                    if (u.getString("wxid").equals(peer)) {
                        String pk = u.getString("pubkey");
                        peerPubCache.put(peer, pk);
                        savePeerPub();
                        return pk;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解密某个消息时该试哪些公钥：当前 + 历史（对方换过密钥也能读旧消息） */
    private List<String> candidatePubs(String peer) {
        List<String> out = new ArrayList<String>();
        if (peer.equals(wxid)) {
            out.add(pubkeyB64);
            return out;
        }
        Friend f = findFriend(peer);
        if (f != null) {
            if (f.pubkey != null) out.add(f.pubkey);
            for (String s : f.oldPubs) if (s != null && !out.contains(s)) out.add(s);
        } else {
            String c = peerPubCache.get(peer);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** 记住旧公钥（最多留 3 个），让换钥前的历史消息仍然能读 */
    private void rememberOldPub(Friend f, String oldPub) {
        if (oldPub == null || oldPub.isEmpty() || oldPub.equals(f.pubkey)) return;
        if (f.oldPubs.contains(oldPub)) return;
        f.oldPubs.add(0, oldPub);
        while (f.oldPubs.size() > 3) f.oldPubs.remove(f.oldPubs.size() - 1);
    }

    /**
     * 拉一个好友「最新」的公钥（发送前 / 解密失败时用）。
     * 换钥后自动更新本地记录 —— 不用重新加好友，跟微信一样还能聊。
     * 网络失败时退回本地缓存，不阻塞发消息。
     */
    private String refreshPeerPub(String peer) {
        if (peer.equals(wxid)) return pubkeyB64;
        Friend f = findFriend(peer);
        try {
            JSONObject r = api.get("/api/pubkeys?ids=" + peer);
            JSONObject us = r.optJSONObject("users");
            JSONObject u = us == null ? null : us.optJSONObject(peer);
            if (u == null) return f != null ? f.pubkey : peerPubCache.get(peer);
            String pk = u.optString("pubkey", "");
            int ver = u.optInt("ver", 1);
            if (pk.isEmpty()) return f != null ? f.pubkey : peerPubCache.get(peer);
            if (f != null) {
                if (!pk.equals(f.pubkey)) {
                    rememberOldPub(f, f.pubkey);
                    f.pubkey = pk;
                    f.ver = ver;
                    saveFriends();
                    syncLog.append("对方换过密钥，已自动更新：").append(peer).append('\n');
                    rewrapGroupsFor(peer, pk);       // 顺手帮他补群密钥
                    final String nickWarn = f.nickname;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("「" + nickWarn + "」换了密钥（换机或找回），已自动同步，可以继续聊");
                        }
                    });
                } else {
                    f.ver = ver;
                }
                return f.pubkey;
            }
            peerPubCache.put(peer, pk);
            savePeerPub();
            return pk;
        } catch (Exception e) {
            return f != null ? f.pubkey : peerPubCache.get(peer);
        }
    }

    /** 一次性把所有好友的公钥对齐服务端（登录后 / 定时调） */
    private void refreshAllFriendKeys() {
        if (friends.isEmpty()) return;
        final List<Friend> snap = new ArrayList<Friend>(friends);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Friend f : snap) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(f.wxid);
                    }
                    JSONObject r = api.get("/api/pubkeys?ids=" + sb);
                    JSONObject us = r.optJSONObject("users");
                    if (us == null) return;
                    boolean changed = false;
                    for (Friend f : snap) {
                        JSONObject u = us.optJSONObject(f.wxid);
                        if (u == null) continue;
                        String pk = u.optString("pubkey", "");
                        int ver = u.optInt("ver", 1);
                        if (!pk.isEmpty() && !pk.equals(f.pubkey)) {
                            rememberOldPub(f, f.pubkey);
                            f.pubkey = pk;
                            f.ver = ver;
                            changed = true;
                            rewrapGroupsFor(f.wxid, pk);
                        } else {
                            f.ver = ver;
                        }
                    }
                    if (changed) {
                        saveFriends();
                        syncLog.append("已自动同步好友新密钥\n");
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** 对方换了密钥 → 我手上要是有群密钥，就替他重新包裹一份（群聊照样能聊） */
    private void rewrapGroupsFor(final String member, final String newPub) {
        if (myPriv == null || newPub == null) return;
        for (final Group g : new ArrayList<Group>(groups)) {
            if (g.key == null || !g.members.contains(member)) continue;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String w = Crypto.wrapKeyForMember(g.key, myPriv, newPub);
                        JSONObject body = new JSONObject();
                        body.put("member", member);
                        body.put("wrapped", w);
                        api.post("/api/groups/" + g.gid + "/rewrap", body);
                        syncLog.append("已为 ").append(member).append(" 补发群密钥\n");
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }

    private void savePeerPub() {
        try {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, String> e : peerPubCache.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            prefs.edit().putString("peerpub", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 界面上显示同步状态；长按可复制详细日志 */
    private TextView statusLine() {
        final TextView tv = new TextView(act);
        syncView = tv;
        tv.setText(syncInfo);
        tv.setTextSize(10);
        tv.setTextColor(0xFF9AA4B2);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(4), 0, dp(2));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle("同步日志")
                        .setMessage(syncLog.length() == 0 ? "（暂无记录）" : syncLog.toString())
                        .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("sync log", syncLog.toString()));
                                toast("已复制");
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("sync log", syncLog.toString()));
                toast("同步日志已复制");
                return true;
            }
        });
        return tv;
    }

    // ---------------- 附件（图片 / 文件） ----------------

    private void dialogAttach() {
        final String[] items = {"拍 照", "从相册选图片", "选择文件"};
        new AlertDialog.Builder(act)
                .setTitle("发送")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) takePhoto();
                        else if (which == 1) pickImage();
                        else pickFile();
                    }
                })
                .show();
    }

    private void takePhoto() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "chat_" + System.currentTimeMillis() + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LinglumChat");
            }
            pendingCamUri = act.getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCamUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            act.startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            toast("打不开相机：" + e.getMessage());
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        act.startActivityForResult(i, REQ_PICK_IMG);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        act.startActivityForResult(i, REQ_PICK_FILE);
    }

    public void activityResult(int req, int result, Intent data) {
        if (req == REQ_CAMERA) {
            if (result == Activity.RESULT_OK && pendingCamUri != null) {
                sendAttachmentFromUri(pendingCamUri, "照片.jpg", true);
            }
            return;
        }
        if (req == REQ_SCAN) {
            if (result == Activity.RESULT_OK && data != null) {
                handleScan(data.getStringExtra("text"));
            }
            return;
        }
        if (req == REQ_AVATAR) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                uploadAvatar(data.getData());
            }
            return;
        }
        if (req == REQ_MOMENT_IMG) {
            if (result == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    android.content.ClipData cd = data.getClipData();
                    for (int i = 0; i < cd.getItemCount() && composePics.size() < 9; i++) {
                        composePics.add(cd.getItemAt(i).getUri());
                    }
                } else if (data.getData() != null && composePics.size() < 9) {
                    composePics.add(data.getData());
                }
                renderComposePics();
            }
            return;
        }
        if (req == REQ_PICK_IMG || req == REQ_PICK_FILE) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                String name = queryName(uri);
                sendAttachmentFromUri(uri, name, req == REQ_PICK_IMG);
            }
            return;
        }
    }

    private String queryName(android.net.Uri uri) {
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String p = uri.getLastPathSegment();
        return p == null ? "文件" : p;
    }

    /** 读字节 → (图片先压缩) → 加密 → 上传 → 发消息 */
    private void sendAttachmentFromUri(final android.net.Uri uri, final String name, final boolean isImage) {
        final String convKey = openChat;
        if (convKey == null) return;
        toast("正在发送…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw;
                    String fname = name;
                    String mime = isImage ? "image/jpeg" : "application/octet-stream";
                    if (isImage) {
                        raw = compressImage(uri);
                        if (!fname.toLowerCase().endsWith(".jpg")) fname = fname + ".jpg";
                    } else {
                        raw = readAll(uri);
                    }
                    if (raw == null || raw.length == 0) throw new Exception("读不到文件内容");
                    if (raw.length > 50 * 1024 * 1024) throw new Exception("文件超过 50MB");

                    String[] p = convKey.split(":", 2);
                    String toType = "u".equals(p[0]) ? "user" : "group";
                    String toId = p[1];
                    SecretKey k;
                    if ("user".equals(toType)) {
                        Friend f = findFriend(toId);
                        if (f == null) throw new Exception("找不到联系人");
                        // v0.0.6：发送前向服务端对齐一次最新公钥 —— 对方找回账号换过密钥也发得出去
                        String pk = refreshPeerPub(toId);
                        if (pk == null || pk.isEmpty()) pk = f.pubkey;
                        k = Crypto.sharedKey(myPriv, pk);
                    } else {
                        Group gr = findGroup(toId);
                        if (gr == null || gr.key == null) throw new Exception("群密钥缺失");
                        k = gr.key;
                    }

                    byte[] enc = Crypto.encryptBytes(k, raw);
                    JSONObject up = api.uploadFile(enc, fname, toType, toId);
                    if (up.optInt("_http", 0) >= 400) {
                        throw new Exception(up.optString("error", "上传失败"));
                    }
                    JSONObject a = new JSONObject();
                    a.put("fid", up.getString("fid"));
                    a.put("name", fname);
                    a.put("size", raw.length);
                    a.put("kind", isImage ? "img" : "file");
                    a.put("mime", mime);
                    sendMessage(isImage ? "[图片]" : "[文件]", a.toString());
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("发送失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private byte[] readAll(android.net.Uri uri) throws Exception {
        InputStream in = act.getContentResolver().openInputStream(uri);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** 图片压缩到长边 1600px、JPEG 80，避免发原图太大 */
    private byte[] compressImage(android.net.Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = act.getContentResolver().openInputStream(uri);
        android.graphics.BitmapFactory.decodeStream(in, null, o);
        in.close();
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = act.getContentResolver().openInputStream(uri);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (bmp == null) throw new Exception("图片解码失败");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        return bos.toByteArray();
    }

    /** 点附件：下载 → 解密 → 打开 */
    private void openAttachment(final Msg m) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(m.attach);
                    String fid = a.getString("fid");
                    String name = a.optString("name", "file");
                    byte[] enc = api.downloadFile(fid);

                    SecretKey k;
                    String conv = convOf(m);
                    String[] p = conv.split(":", 2);
                    if ("g".equals(p[0])) {
                        Group gr = findGroup(p[1]);
                        if (gr == null || gr.key == null) throw new Exception("群密钥缺失");
                        k = gr.key;
                    } else {
                        String peer = p[1];
                        // 附件也可能是在对方换钥前发的 → 逐个候选公钥试
                        SecretKey kk = null;
                        for (String pk : candidatePubs(peer)) {
                            try {
                                kk = Crypto.sharedKey(myPriv, pk);
                                Crypto.decryptBytes(kk, enc);   // 能解开就用它
                                break;
                            } catch (Exception ig) {
                                kk = null;
                            }
                        }
                        if (kk == null) throw new Exception("缺少对方公钥（或对方换过密钥）");
                        k = kk;
                    }
                    byte[] raw = Crypto.decryptBytes(k, enc);

                    File dir = new File(act.getCacheDir(), "attachments");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(raw);
                    fos.close();

                    final String mime = a.optString("mime", "application/octet-stream");
                    final File f2 = out;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            openLocal(f2, mime);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("打开失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void openLocal(File f, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.net.Uri.parse("content://" + act.getPackageName()
                    + ".attach/" + f.getName()), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast("没有能打开它的应用");
        }
    }

    /** 设置：后台接收消息开关 */
    private void dialogSettings() {
        final boolean on = prefs.getBoolean("bg_service", true);
        final boolean[] checked = {on};
        new AlertDialog.Builder(act)
                .setTitle("设置")
                .setMultiChoiceItems(new CharSequence[]{"后台接收消息（通知栏会挂一条常驻通知）"},
                        new boolean[]{on},
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                                checked[0] = isChecked;
                            }
                        })
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putBoolean("bg_service", checked[0]).apply();
                        if (checked[0]) {
                            startChatService();
                            toast("已开启后台接收消息");
                        } else {
                            act.stopService(new Intent(act, ChatService.class));
                            toast("已关闭后台接收（只有打开 App 时才收消息）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 检查更新 ----------------

    private void checkUpdate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // /api/version 是公开接口，未登录也能查（登录页也能提示更新）
                    Api vApi = new Api(baseUrl, token);
                    // ① 先看热更新补丁：同一个壳就能用，不用装安装包
                    if (tryHotPatch(vApi)) return;
                    // ② 再看整包更新
                    JSONObject r = vApi.get("/api/version");
                    final int remoteCode = r.optInt("versionCode", 0);
                    final String remoteName = r.optString("versionName", "");
                    final String url = r.optString("url", "");
                    final String md5 = r.optString("md5", "");
                    final String notes = r.optString("notes", "");
                    final int minCode = r.optInt("minVersionCode", 0);
                    final int mine = myVersionCode();
                    if (remoteCode <= mine || url.isEmpty()) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                updateDialogShown = false;
                            }
                        });
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(remoteName, mine, notes, url, md5,
                                    mine < minCode);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private int myVersionCode() {
        try {
            android.content.pm.PackageInfo pi =
                    act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 热更新 ----------------

    private String myVersionName() {
        try {
            android.content.pm.PackageInfo pi =
                    act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 设置页「热更新」：显示壳版本 / 补丁版本 / 上次异常，可检查或回滚 */
    private void dialogHotfix() {
        final int shellCode = myVersionCode();
        int applied = HotLoader.appliedVersion(act);
        String err = HotLoader.lastError(act);
        StringBuilder sb = new StringBuilder();
        sb.append("安装包（壳）：").append(myVersionName()).append("（").append(shellCode).append("）\n");
        sb.append("当前代码：").append(BUILD_TAG).append('\n');
        sb.append("热更新补丁：").append(applied > 0 ? ("v" + applied + " 生效中") : "无（用包里自带的实现）");
        if (err != null && !err.isEmpty()) sb.append("\n上次异常：").append(err);
        sb.append("\n\n小改动只下发补丁（几十 KB），下载后重启即生效；"
                + "改图标/权限/组件这类才需要重装安装包。");

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle("🧩 热更新")
                .setMessage(sb.toString())
                .setPositiveButton("检查更新", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        updateDialogShown = false;
                        checkUpdate();
                        toast("正在检查…");
                    }
                });
        if (applied > 0) {
            b.setNeutralButton("回滚到内置版本", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    new AlertDialog.Builder(act)
                            .setTitle("回滚热更新")
                            .setMessage("删除已安装的补丁，重启后回到安装包自带的版本。\n（下次联网还能再装上最新补丁）")
                            .setPositiveButton("回滚", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d2, int w2) {
                                    HotLoader.discardPatch(act, "用户手动回滚");
                                    toast("已回滚，重启 App 生效");
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }
        b.setNegativeButton("关闭", null);
        b.show();
    }

    /** 检查并安装热更新补丁；装上了就返回 true（不再走整包更新） */
    private boolean tryHotPatch(Api vApi) {
        try {
            JSONObject p = vApi.get("/api/patch/latest?shell=" + myVersionCode());
            final int pv = p.optInt("patchVersion", 0);
            int minShell = p.optInt("minShell", 0);
            String url = p.optString("url", "");
            String sigB64 = p.optString("sig", "");
            final String notes = p.optString("notes", "");
            int applied = HotLoader.appliedVersion(act);
            if (pv <= applied || url.isEmpty() || sigB64.isEmpty() || minShell > myVersionCode()) {
                return false;
            }
            byte[] dex = vApi.getBytes(url);
            if (dex == null || dex.length < 1024) return false;
            byte[] sig = android.util.Base64.decode(sigB64, android.util.Base64.DEFAULT);
            HotLoader.installPatch(act, dex, sig, pv, notes);   // 里面有验签，失败会抛
            syncLog.append("热更新已安装 v").append(pv).append('\n');
            ui.post(new Runnable() {
                @Override
                public void run() {
                    updateDialogShown = false;
                    new AlertDialog.Builder(act)
                            .setTitle("热更新已就绪（v" + pv + "）")
                            .setMessage((notes.isEmpty() ? "" : notes + "\n\n")
                                    + "已下载并验签通过，重启 App 生效。")
                            .setPositiveButton("立即重启", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    act.recreate();
                                }
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                }
            });
            return true;
        } catch (Throwable t) {
            syncLog.append("热更新失败：").append(t).append('\n');
            return false;
        }
    }

    private void showUpdateDialog(String name, int mine, String notes,
                                  final String url, final String md5, boolean forced) {
        if (updateDialogShown) return;
        updateDialogShown = true;
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle((forced ? "必须更新：" : "发现新版本 ") + name)
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n")
                        + "当前版本号：" + mine
                        + (forced ? "\n\n⚠️ 这是强制更新，不更新将无法继续使用。"
                                  : "\n\n建议更新以获得最新修复。"))
                .setPositiveButton("立即更新", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        downloadAndInstall(url, md5);
                    }
                });
        if (forced) {
            b.setCancelable(false);
            b.setNegativeButton("退出", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    act.finish();
                }
            });
        } else {
            b.setNegativeButton("以后再说", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    updateDialogShown = false;   // 下次启动还能再提示
                }
            });
        }
        b.show();
    }

    /** 服务端返回 426（版本过低被拒）时弹一次强制更新，绝不重复弹 */
    private void forceUpdate() {
        if (updateDialogShown) return;
        updateChecked = true;
        checkUpdate();
    }

    private void downloadAndInstall(final String url, final String md5) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            toast("请先允许「灵珑聊天」安装应用");
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在下载更新")
                .setMessage("0%")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(act.getCacheDir(), "update");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
                    File out = new File(dir, "linglum-chat.apk");
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(60000);
                    int total = c.getContentLength();
                    java.io.InputStream in = c.getInputStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                    byte[] buf = new byte[16384];
                    int n;
                    long done = 0;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != lastPct) {
                            lastPct = pct;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    dlg.setMessage(pct + "%");
                                }
                            });
                        }
                    }
                    fos.close();
                    in.close();
                    String got = hex(md.digest());
                    if (md5 != null && !md5.isEmpty() && !got.equalsIgnoreCase(md5)) {
                        out.delete();
                        throw new Exception("文件校验失败（MD5 不符）");
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            dlg.dismiss();
                            installApk(out);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            dlg.dismiss();
                            toast("更新失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void installApk(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.net.Uri.parse("content://" + act.getPackageName() + ".apk/" + f.getName()),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast("无法调起安装器：" + e.getMessage());
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------- 本地存储 ----------------

    private File msgFile() {
        return new File(act.getFilesDir(), "messages.jsonl");
    }

    private void saveMessage(Msg m) {
        try {
            FileOutputStream os = new FileOutputStream(msgFile(), true);
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("from", m.from);
            o.put("to_type", m.toType);
            o.put("to_id", m.toId);
            o.put("payload", m.payload);
            o.put("sig", m.sig == null ? "" : m.sig);
            o.put("a", m.attach == null ? "" : m.attach);
            o.put("ts", m.ts);
            o.put("rc", m.recalled ? 1 : 0);
            os.write((o.toString() + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {
        }
    }

    /** 正在按本地历史重建消息链（此时不判重放/断链，只推进状态） */
    private boolean rebuildingHistory = false;

    private void loadLocalState() {
        // 好友
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Friend f = new Friend();
                f.wxid = o.getString("wxid");
                f.nickname = o.optString("nickname", f.wxid);
                f.pubkey = o.getString("pubkey");
                f.ver = o.optInt("ver", 1);
                f.avatarVer = o.optInt("avatarVer", 0);
                JSONArray op = o.optJSONArray("oldpubs");
                if (op != null) {
                    for (int j = 0; j < op.length(); j++) f.oldPubs.add(op.getString(j));
                }
                friends.add(f);
            }
        } catch (Exception ignored) {
        }
        // 群
        try {
            JSONArray arr = new JSONArray(prefs.getString("groups", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Group g = new Group();
                g.gid = o.getString("gid");
                g.name = o.getString("name");
                g.owner = o.optString("owner");
                g.myWrapped = o.optString("my_wrapped");
                JSONArray adm0 = o.optJSONArray("admins");
                if (adm0 != null) for (int j = 0; j < adm0.length(); j++) g.admins.add(adm0.getString(j));
                g.nameAdminOnly = o.optInt("name_admin_only", 0);
                JSONObject mn0 = o.optJSONObject("member_names");
                if (mn0 != null) {
                    java.util.Iterator<String> it0 = mn0.keys();
                    while (it0.hasNext()) {
                        String k0 = it0.next();
                        g.memberNames.put(k0, mn0.optString(k0, ""));
                    }
                }
                String keyB64 = o.optString("key", "");
                if (!keyB64.isEmpty()) {
                    g.key = new javax.crypto.spec.SecretKeySpec(
                            android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP), "AES");
                }
                groups.add(g);
            }
        } catch (Exception ignored) {
        }
        // 对方公钥缓存
        try {
            JSONObject o = new JSONObject(prefs.getString("peerpub", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                peerPubCache.put(k, o.getString(k));
            }
        } catch (Exception ignored) {
        }
        // 消息历史
        cursor = prefs.getLong("cursor", 0);
        // v1.1：修复「自己发的消息拉不到」→ 首次运行把游标归零，重新同步一遍历史
        // （本地已有消息按 id 去重，不会重复）
        if (prefs.getInt("app_ver", 1) < 3) {
            // v1.7：后台服务曾推进共享游标，导致部分消息被跳过 → 归零重同步一次
            //（本地已有消息按 id 去重，不会重复显示）
            cursor = 0L;
            prefs.edit().putInt("app_ver", 3).putLong("cursor", 0L).apply();
        }
        try {
            File f = msgFile();
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject o = new JSONObject(line);
                        Msg m = new Msg();
                        m.id = o.optLong("id");
                        m.from = o.optString("from");
                        m.toType = o.optString("to_type");
                        m.toId = o.optString("to_id");
                        m.payload = o.optString("payload");
                        m.sig = o.optString("sig");
                        String att = o.optString("a", "");
                        m.attach = att.isEmpty() ? null : att;
                        m.ts = o.optLong("ts");
                        m.recalled = o.optInt("rc", 0) == 1;
                        messages.add(m);
                    } catch (Exception ignored) {
                    }
                }
                r.close();
            }
        } catch (Exception ignored) {
        }
        // 解密历史：先把链状态清掉，按历史顺序重建（否则上次的 lastSeq 会把老消息判成"重放"）
        try {
            SharedPreferences.Editor e2 = prefs.edit();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith("chain_")) e2.remove(k);
            }
            e2.apply();
        } catch (Exception ignored) {
        }
        rebuildingHistory = true;
        if (myPriv != null) {
            for (Msg m : messages) decrypt(m);
        }
        rebuildingHistory = false;
    }

    private void saveFriends() {
        try {
            JSONArray arr = new JSONArray();
            for (Friend f : friends) {
                JSONObject o = new JSONObject();
                o.put("wxid", f.wxid);
                o.put("nickname", f.nickname);
                o.put("pubkey", f.pubkey);
                o.put("ver", f.ver);
                o.put("avatarVer", f.avatarVer);
                JSONArray op = new JSONArray();
                for (String s : f.oldPubs) op.put(s);
                o.put("oldpubs", op);
                arr.put(o);
            }
            prefs.edit().putString("friends", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void saveGroups() {
        try {
            JSONArray arr = new JSONArray();
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("gid", g.gid);
                o.put("name", g.name);
                o.put("owner", g.owner);
                o.put("my_wrapped", g.myWrapped);
                o.put("admins", new JSONArray(g.admins));
                o.put("name_admin_only", g.nameAdminOnly);
                o.put("notice", g.notice);
                o.put("allow_member_invite", g.allowMemberInvite);
                JSONObject mn = new JSONObject();
                for (String k2 : g.memberNames.keySet()) mn.put(k2, g.memberNames.get(k2));
                o.put("member_names", mn);
                if (g.key != null) {
                    o.put("key", android.util.Base64.encodeToString(
                            g.key.getEncoded(), android.util.Base64.NO_WRAP));
                }
                arr.put(o);
            }
            prefs.edit().putString("groups", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private Friend findFriend(String id) {
        for (Friend f : friends) if (f.wxid.equals(id)) return f;
        return null;
    }

    private Group findGroup(String gid) {
        for (Group g : groups) if (g.gid.equals(gid)) return g;
        return null;
    }

    // ---------------- 组件小工具 ----------------

    private EditText input(String hint, String value) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setText(value);
        et.setTextColor(textMain());
        et.setHintTextColor(textSub());
        et.setPadding(dp(6), dp(12), dp(6), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        et.setLayoutParams(lp);
        return et;
    }

    private Button smallBtn(String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(accent);
        b.setBackground(glass(18));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private GradientDrawable buttonBg() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accent, blend(accent, 0xFFFFFFFF, 0.28f)});
        g.setCornerRadius(dp(27));
        return g;
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
