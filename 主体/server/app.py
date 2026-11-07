#!/usr/bin/env python3
"""
灵珑聊天 —— NAS 服务端（v0.0.1 安全加固版）
================================================
职责（刻意保持最小）：
  · 账号：专属号 + 密码登录（PBKDF2 哈希，永不存明文）
  · 公钥目录：供加好友、做 ECDH
  · 私钥托管：只存「用密码 / 救援口令加密过的」私钥副本（服务端解不开）
  · 消息存储转发：只存密文
  · 群组：成员表 + 每人一份被各自公钥加密的群密钥

安全措施（本轮新增）：
  · 全局限流（按 IP + 路由组）
  · 登录炸弹防护（同号/同 IP 连续失败锁定）
  · 请求体大小上限
  · 安全响应头（nosniff / DENY / no-referrer / HSTS / CSP）
  · 所有入参长度与格式校验
  · 审计日志（JSONL，只记录动作与结果，不记录内容）
  · 统一错误处理，绝不向客户端抛栈
"""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from functools import wraps

from flask import Flask, g, jsonify, request, send_file, send_from_directory

DATA_DIR = os.environ.get("CHAT_DATA_DIR", os.path.join(os.path.dirname(__file__), "data"))
DB_PATH = os.path.join(DATA_DIR, "chat.db")
AUDIT_LOG = os.path.join(DATA_DIR, "audit.jsonl")
APK_DIR = os.path.join(DATA_DIR, "apk")
FILES_DIR = os.path.join(DATA_DIR, "files")
AVATAR_DIR = os.path.join(DATA_DIR, "avatars")
MOMENT_DIR = os.path.join(DATA_DIR, "moments")   # 朋友圈图片（客户端加密后的密文块）
MAX_MOMENT_IMGS = 9
MAX_MOMENT_IMG = 3 * 1024 * 1024                 # 单张密文上限 3MB
MAX_AVATAR = 400 * 1024            # 头像上限 400KB（客户端压到 256px 才传）

TOKEN_TTL = 60 * 60 * 24 * 30          # 登录有效期 30 天
PBKDF2_ROUNDS = 200000
MAX_FILE = 50 * 1024 * 1024            # 单文件 50MB
MAX_BODY = MAX_FILE + 1024 * 1024      # 请求体上限（含余量）
MAX_PAYLOAD = 8 * 1024 * 1024          # 单条消息密文上限

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_BODY

# ---------------- 限流 ----------------

_LIMIT_LOCK = threading.Lock()
_RATE = {}          # key -> [窗口起点, 次数]


def rate_limit(key, limit, window=60):
    """简单令牌窗口限流：整体加锁，避免多线程竞争"""
    now = time.time()
    with _LIMIT_LOCK:
        if len(_RATE) > 20000:            # 防止内存无限增长
            _RATE.clear()
        rec = _RATE.get(key)
        if not rec or now - rec[0] > window:
            _RATE[key] = [now, 1]
            return True
        rec[1] += 1
        return rec[1] <= limit


_LOGIN_FAILS = {}
_LOGIN_FAILS_ACCT = {}     # 只按账号计（换 IP 也绕不过）
LOGIN_MAX_FAILS = 5
LOGIN_LOCK_SECONDS = 60

# ---------------- 审计 ----------------

_AUDIT_LOCK = threading.Lock()


def audit(action, result, wxid="-", extra=None):
    """只记录动作/结果/账号/IP，绝不记录消息内容或密钥"""
    try:
        rec = {
            "t": int(time.time()),
            "ip": client_ip(),
            "wxid": wxid,
            "action": action,
            "result": result,
        }
        if extra:
            rec.update(extra)
        line = json.dumps(rec, ensure_ascii=False)
        with _AUDIT_LOCK:
            os.makedirs(DATA_DIR, exist_ok=True)
            with open(AUDIT_LOG, "a", encoding="utf-8") as f:
                f.write(line + "\n")
    except Exception:
        pass


def client_ip():
    """nginx 用 $remote_addr 覆盖 XFF，这里取最后一个更安全"""
    xff = request.headers.get("X-Forwarded-For", "")
    if xff:
        return xff.split(",")[-1].strip()[:64]
    return request.remote_addr or "-"


# ---------------- 数据库 ----------------

def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH, timeout=15)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
        g.db.execute("PRAGMA foreign_keys=ON")
        g.db.execute("PRAGMA busy_timeout=15000")     # 并发写时别立刻 SQLITE_BUSY
        g.db.execute("PRAGMA synchronous=NORMAL")     # WAL 下既快又足够安全
    return g.db


@app.teardown_appcontext
def close_db(exc):
    d = g.pop("db", None)
    if d is not None:
        d.close()


def init_db():
    os.makedirs(DATA_DIR, exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.executescript("""
    CREATE TABLE IF NOT EXISTS users (
        wxid         TEXT PRIMARY KEY,
        nickname     TEXT NOT NULL,
        pubkey       TEXT NOT NULL,
        privkey_enc  TEXT NOT NULL,
        kdf_salt     TEXT NOT NULL,
        pw_hash      TEXT NOT NULL,
        pw_salt      TEXT NOT NULL,
        created      INTEGER NOT NULL,
        privkey_rescue TEXT NOT NULL DEFAULT '',
        rescue_hash  TEXT NOT NULL DEFAULT '',
        rescue_salt  TEXT NOT NULL DEFAULT '',
        rescue_kek_salt TEXT NOT NULL DEFAULT '',
        pubkey_ver   INTEGER NOT NULL DEFAULT 1,
        avatar_ver   INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS tokens (
        token   TEXT PRIMARY KEY,
        wxid    TEXT NOT NULL,
        created INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_tokens_wxid ON tokens(wxid);
    CREATE TABLE IF NOT EXISTS messages (
        id        INTEGER PRIMARY KEY AUTOINCREMENT,
        to_type   TEXT NOT NULL,
        to_id     TEXT NOT NULL,
        from_wxid TEXT NOT NULL,
        payload   TEXT NOT NULL,
        sig       TEXT NOT NULL DEFAULT '',
        ts        INTEGER NOT NULL,
        recalled  INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_msg_to ON messages(to_type, to_id, id);
    CREATE INDEX IF NOT EXISTS idx_msg_from ON messages(from_wxid, id);
    CREATE TABLE IF NOT EXISTS groups (
        gid     TEXT PRIMARY KEY,
        name    TEXT NOT NULL,
        owner   TEXT NOT NULL,
        members TEXT NOT NULL,
        admins  TEXT NOT NULL DEFAULT '[]',
        name_admin_only INTEGER NOT NULL DEFAULT 0,
        notice  TEXT NOT NULL DEFAULT '',
        allow_member_invite INTEGER NOT NULL DEFAULT 1,
        created INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS files (
        fid     TEXT PRIMARY KEY,
        owner   TEXT NOT NULL,
        to_type TEXT NOT NULL,
        to_id   TEXT NOT NULL,
        name    TEXT NOT NULL,
        size    INTEGER NOT NULL,
        created INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_files_to ON files(to_type, to_id);
    CREATE TABLE IF NOT EXISTS group_invites (
        token   TEXT PRIMARY KEY,
        gid     TEXT NOT NULL,
        created INTEGER NOT NULL,
        expires INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS group_keys (
        gid     TEXT NOT NULL,
        wxid    TEXT NOT NULL,
        wrapped TEXT NOT NULL,
        wrapped_by TEXT NOT NULL DEFAULT '',
        PRIMARY KEY (gid, wxid)
    );
    CREATE TABLE IF NOT EXISTS friends (
        owner     TEXT NOT NULL,
        friend    TEXT NOT NULL,
        created   INTEGER NOT NULL,
        state     INTEGER NOT NULL DEFAULT 1,     -- 0=申请中 1=好友（老数据默认 1）
        PRIMARY KEY (owner, friend)
    );
    CREATE TABLE IF NOT EXISTS recover_codes (
        wxid        TEXT PRIMARY KEY,
        key128      TEXT NOT NULL,
        approved_by TEXT NOT NULL DEFAULT '',
        expires     INTEGER NOT NULL,
        used        INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS moments (
        mid         TEXT PRIMARY KEY,
        author      TEXT NOT NULL,
        text_enc    TEXT NOT NULL DEFAULT '',
        n_images    INTEGER NOT NULL DEFAULT 0,
        created     INTEGER NOT NULL,
        deleted     INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS moment_keys (
        mid         TEXT NOT NULL,
        wxid        TEXT NOT NULL,
        wrapped     TEXT NOT NULL,
        PRIMARY KEY (mid, wxid)
    );
    CREATE TABLE IF NOT EXISTS moment_likes (
        mid         TEXT NOT NULL,
        wxid        TEXT NOT NULL,
        created     INTEGER NOT NULL,
        PRIMARY KEY (mid, wxid)
    );
    CREATE TABLE IF NOT EXISTS moment_comments (
        cid         TEXT PRIMARY KEY,
        mid         TEXT NOT NULL,
        wxid        TEXT NOT NULL,
        text_enc    TEXT NOT NULL,
        created     INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_moments_created ON moments(created);
    """)
    migrate(con)
    con.commit()
    con.close()


def migrate(con):
    """老库补字段（新字段一律带默认值，保证老数据可用）"""
    mcols = [r[1] for r in con.execute("PRAGMA table_info(messages)")]
    if mcols and "sig" not in mcols:
        con.execute("ALTER TABLE messages ADD COLUMN sig TEXT NOT NULL DEFAULT ''")
    if mcols and "recalled" not in mcols:
        con.execute("ALTER TABLE messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0")
    ucols = [r[1] for r in con.execute("PRAGMA table_info(users)")]
    for col in ("privkey_rescue", "rescue_hash", "rescue_salt", "rescue_kek_salt"):
        if ucols and col not in ucols:
            con.execute("ALTER TABLE users ADD COLUMN %s TEXT NOT NULL DEFAULT ''" % col)
    # v0.0.6：公钥版本号（换密钥对时 +1，客户端靠它/靠比对发现对方换钥）
    if ucols and "pubkey_ver" not in ucols:
        con.execute("ALTER TABLE users ADD COLUMN pubkey_ver INTEGER NOT NULL DEFAULT 1")
    if ucols and "avatar_ver" not in ucols:
        con.execute("ALTER TABLE users ADD COLUMN avatar_ver INTEGER NOT NULL DEFAULT 0")
    if ucols and "phone_hash" not in ucols:
        con.execute("ALTER TABLE users ADD COLUMN phone_hash TEXT")
    if ucols and "email_hash" not in ucols:
        con.execute("ALTER TABLE users ADD COLUMN email_hash TEXT")
    con.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone_hash)")
    con.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email_hash)")
    gcols = [r[1] for r in con.execute("PRAGMA table_info(group_keys)")]
    if gcols and "wrapped_by" not in gcols:
        con.execute("ALTER TABLE group_keys ADD COLUMN wrapped_by TEXT NOT NULL DEFAULT ''")
    # v0.2.8：群管理（管理员 / 仅管理员可改名）
    grcols = [r[1] for r in con.execute("PRAGMA table_info(groups)")]
    if grcols and "admins" not in grcols:
        con.execute("ALTER TABLE groups ADD COLUMN admins TEXT NOT NULL DEFAULT '[]'")
    if grcols and "name_admin_only" not in grcols:
        con.execute("ALTER TABLE groups ADD COLUMN name_admin_only INTEGER NOT NULL DEFAULT 0")
    if grcols and "notice" not in grcols:
        con.execute("ALTER TABLE groups ADD COLUMN notice TEXT NOT NULL DEFAULT ''")
    if grcols and "allow_member_invite" not in grcols:
        con.execute("ALTER TABLE groups ADD COLUMN allow_member_invite INTEGER NOT NULL DEFAULT 1")
    # v0.1.4：好友要对方通过（老数据默认 1，现存好友关系不受影响）
    fcols = [r[1] for r in con.execute("PRAGMA table_info(friends)")]
    if fcols and "state" not in fcols:
        con.execute("ALTER TABLE friends ADD COLUMN state INTEGER NOT NULL DEFAULT 1")
    # v0.1.4：群密钥包裹记录「包裹时对方公钥版本」——只有版本变了才允许别人覆盖
    gkcols = [r[1] for r in con.execute("PRAGMA table_info(group_keys)")]
    if gkcols and "ver" not in gkcols:
        con.execute("ALTER TABLE group_keys ADD COLUMN ver INTEGER NOT NULL DEFAULT 0")
    # v0.1.4：扫码入群 token 记录已用次数（防止一张码被无限传播）
    gicols = [r[1] for r in con.execute("PRAGMA table_info(group_invites)")]
    if gicols and "uses" not in gicols:
        con.execute("ALTER TABLE group_invites ADD COLUMN uses INTEGER NOT NULL DEFAULT 0")
    con.commit()


_PEPPER = None


def _pepper():
    """手机号/邮箱哈希用的服务端密钥：优先环境变量，其次 data/.pepper（首次自动生成）"""
    global _PEPPER
    if _PEPPER:
        return _PEPPER
    p = os.environ.get("CHAT_PEPPER") or ""
    if not p:
        f = os.path.join(DATA_DIR, ".pepper")
        try:
            if os.path.exists(f):
                p = open(f).read().strip()
            if not p:
                p = secrets.token_hex(32)
                with open(f, "w") as fh:
                    fh.write(p)
                os.chmod(f, 0o600)
        except Exception:
            p = "fallback-pepper-" + hashlib.sha256(DATA_DIR.encode()).hexdigest()[:16]
    _PEPPER = p
    return p


def _hash_contact(kind, value):
    """kind='p' 手机号 / 'e' 邮箱；加服务端密钥做 HMAC，库里只存哈希"""
    return hmac.new(_pepper().encode(), (kind + ":" + value).encode("utf-8"),
                    hashlib.sha256).hexdigest()


def _norm_phone(s):
    return re.sub(r"[^0-9+]", "", s or "")


def _norm_email(s):
    return (s or "").strip().lower()


def hash_pw(password, salt_hex=None):
    salt = bytes.fromhex(salt_hex) if salt_hex else secrets.token_bytes(16)
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt,
                               PBKDF2_ROUNDS, 32).hex(), salt.hex()


def check_pw(password, salt_hex, expected_hash):
    if not expected_hash or not salt_hex:
        return False
    h, _ = hash_pw(password, salt_hex)
    return hmac.compare_digest(h, expected_hash)


# ---------------- 输入校验 ----------------

RE_WXID = re.compile(r"^[A-Za-z0-9_]{3,32}$")


def bad(msg, code=400):
    return jsonify({"error": msg}), code


def clean(s, maxlen=64):
    return (s or "").strip()[:maxlen]


def _like_lit(v):
    """LIKE 字面量：把 % _ \\ 转义掉（配合 ESCAPE '\\' 用）。
    wxid 允许下划线，而下划线在 LIKE 里是"任意一个字符"——不转义会串到别人的群。"""
    return (v or "").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _tok_hash(t):
    """令牌只存哈希：库文件被读走也不能直接拿去冒充别人"""
    return "h:" + hashlib.sha256(("linglum:" + (t or "")).encode()).hexdigest()


def _pubkey_ver(wxid):
    r = db().execute("SELECT pubkey_ver FROM users WHERE wxid=?", (wxid,)).fetchone()
    return int((r["pubkey_ver"] if r and r["pubkey_ver"] is not None else 1) or 1)


def _mutual_friends(other):
    """双方都通过（或老数据双向都在）才算好友"""
    a = db().execute("SELECT state FROM friends WHERE owner=? AND friend=?",
                     (g.wxid, other)).fetchone()
    b = db().execute("SELECT state FROM friends WHERE owner=? AND friend=?",
                     (other, g.wxid)).fetchone()
    return bool(a and b and int(a["state"] or 0) == 1 and int(b["state"] or 0) == 1)


# ---------------- 鉴权 ----------------

def auth_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        auth = request.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if not token or len(token) > 128:
            return jsonify({"error": "unauthorized"}), 401
        th = _tok_hash(token)
        row = db().execute("SELECT wxid, created FROM tokens WHERE token=?",
                           (th,)).fetchone()
        if not row:                       # 旧版明文令牌：命中后立刻升级成哈希
            legacy = db().execute("SELECT wxid, created FROM tokens WHERE token=?",
                                  (token,)).fetchone()
            if legacy:
                db().execute("DELETE FROM tokens WHERE token=?", (token,))
                db().execute("INSERT OR REPLACE INTO tokens(token,wxid,created)"
                             " VALUES(?,?,?)", (th, legacy["wxid"], legacy["created"]))
                db().commit()
                row = legacy
        if row and int(row["created"]) + TOKEN_TTL < int(time.time()):
            db().execute("DELETE FROM tokens WHERE token=?", (token,))
            db().commit()
            row = None
        if not row:
            return jsonify({"error": "unauthorized"}), 401
        if not db().execute("SELECT 1 FROM users WHERE wxid=?", (row["wxid"],)).fetchone():
            db().execute("DELETE FROM tokens WHERE token=?", (token,))
            db().commit()
            return jsonify({"error": "account_deleted",
                            "message": "该账号在服务端已不存在"}), 401
        g.wxid = row["wxid"]
        g.token = th
        return fn(*args, **kwargs)
    return wrapper


# ---------------- 全局中间件 ----------------

@app.before_request
def security_guard():
    """统一入口：限流 → 强制更新闸门"""
    path = request.path
    if request.method == "DELETE" and path.startswith("/api/friends/"):
        print("[del] DELETE %s ip=%s ver=%s" % (
            path, client_ip(), request.headers.get("X-App-Version", "-")), flush=True)

    # 静态下载与健康检查不限流（长轮询单独给宽松额度）
    if path != "/api/health" and not path.startswith("/apk/") \
            and not path.startswith("/api/patch/"):
        if path == "/api/messages":
            # 长轮询：每连接最多挂 25s，给足额度
            if not rate_limit("msg:" + client_ip(), limit=240, window=60):
                return jsonify({"error": "请求过于频繁"}), 429
        elif path in ("/api/register", "/api/login", "/api/recovery/apply",
                      "/api/recovery/approve", "/api/recovery/finish"):
            if not rate_limit("auth:" + client_ip(), limit=30, window=60):
                audit("rate_limit", "blocked", extra={"path": path})
                return jsonify({"error": "操作过于频繁，请稍后再试"}), 429
        elif path == "/api/friends":
            # 好友申请：防"一键群发好友申请"骚扰
            if not rate_limit("friend:" + client_ip(), limit=40, window=60):
                audit("rate_limit", "blocked", extra={"path": path})
                return jsonify({"error": "操作过于频繁，请稍后再试"}), 429
        else:
            if not rate_limit("api:" + client_ip(), limit=300, window=60):
                return jsonify({"error": "请求过于频繁"}), 429

    # 强制更新闸门
    if path.startswith("/apk/") or path.startswith("/api/patch/") \
            or path in ("/api/version", "/api/health"):
        return None
    min_code = int(_version_json().get("minVersionCode", 0) or 0)
    if min_code <= 0:
        return None
    try:
        v = int(request.headers.get("X-App-Version", "0"))
    except ValueError:
        v = 0
    if v < min_code:
        return jsonify({"error": "upgrade_required",
                        "message": "当前客户端版本过低，必须更新后才能继续使用",
                        "minVersionCode": min_code}), 426
    return None


@app.after_request
def harden(resp):
    """安全响应头 + 轻量访问日志"""
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "DENY"
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["X-XSS-Protection"] = "0"
    resp.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
    resp.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"
    if request.path.startswith("/api/") or request.path.startswith("/apk/"):
        resp.headers["Cache-Control"] = "no-store"
    if request.is_secure:
        resp.headers["Strict-Transport-Security"] = "max-age=31536000"

    if request.path.startswith("/api/friends"):
        try:
            who = "-"
            auth = request.headers.get("Authorization", "")
            if auth.startswith("Bearer "):
                r = db().execute("SELECT wxid FROM tokens WHERE token=?",
                                 (_tok_hash(auth[7:]),)).fetchone()
                who = r["wxid"] if r else "?"
            print("[friend] %s %s user=%s -> %s" % (
                request.method, request.path, who, resp.status_code), flush=True)
        except Exception:
            pass

    if request.path in ("/api/messages", "/api/version"):
        try:
            who = "-"
            auth = request.headers.get("Authorization", "")
            if auth.startswith("Bearer "):
                r = db().execute("SELECT wxid FROM tokens WHERE token=?",
                                 (_tok_hash(auth[7:]),)).fetchone()
                who = r["wxid"] if r else "?"
            print("[poll] %s %s since=%s ver=%s user=%s -> %s" % (
                request.method, request.path, request.args.get("since", "-")[:12],
                request.headers.get("X-App-Version", "-"), who, resp.status_code), flush=True)
        except Exception:
            pass
    return resp


@app.errorhandler(413)
def too_large(e):
    return jsonify({"error": "请求体过大"}), 413


@app.errorhandler(Exception)
def on_error(e):
    """绝不把栈暴露给客户端"""
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return e
    try:
        print("[error] %s: %s" % (request.path, e), flush=True)
        audit("exception", "error", extra={"path": request.path, "err": str(e)[:120]})
    except Exception:
        pass
    return jsonify({"error": "服务器内部错误"}), 500


# ---------------- 版本 / 更新 ----------------

_VER_CACHE = {"mtime": 0, "data": {}}


def _version_json():
    p = os.path.join(APK_DIR, "version.json")
    try:
        mt = os.path.getmtime(p)
    except OSError:
        return {}
    if _VER_CACHE["mtime"] == mt:
        return _VER_CACHE["data"]
    try:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        _VER_CACHE["mtime"] = mt
        _VER_CACHE["data"] = data
        return data
    except Exception:
        return {}


@app.get("/api/health")
def health():
    return jsonify({"ok": True, "service": "linglong-chat"})


@app.get("/api/version")
def version_info():
    info = _version_json()
    if not info:
        return jsonify({"versionCode": 0, "versionName": "0", "url": "", "md5": "",
                        "notes": "", "minVersionCode": 0})
    fn = os.path.basename(info.get("file", ""))
    info["url"] = request.host_url.rstrip("/") + "/apk/" + fn if fn else ""
    info["file"] = fn
    info.setdefault("minVersionCode", 0)
    return jsonify(info)


@app.get("/apk/<path:name>")
def download_apk(name):
    safe = os.path.basename(name)
    if not safe.endswith(".apk"):
        return jsonify({"error": "只提供 apk 下载"}), 404
    return send_from_directory(APK_DIR, safe, as_attachment=True)


# ---------------- 热更新补丁 ----------------

PATCH_DIR = os.path.join(DATA_DIR, "patch")


def _patch_json():
    p = os.path.join(PATCH_DIR, "version.json")
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


@app.get("/api/patch/latest")
def patch_latest():
    """最新热更新补丁（公开接口：壳/老客户端都能查，不需要登录）"""
    j = _patch_json()
    if not j or not j.get("file"):
        return jsonify({"patchVersion": 0})
    out = dict(j)
    out["url"] = "/api/patch/" + os.path.basename(out["file"])
    return jsonify(out)


@app.get("/api/patch/<path:name>")
def patch_file(name):
    safe = os.path.basename(name)
    if not (safe.endswith(".dex") or safe.endswith(".sig")):
        return jsonify({"error": "not found"}), 404
    return send_from_directory(PATCH_DIR, safe, as_attachment=False)




# ---------------- 高级诊断会话（求助码 = 授权运维远程体检/修复） ----------------
# 安全模型：
#   · 客户端自己开一个 30 分钟有效的求助码（一次性会话）；
#   · 运维必须持有「运维密钥」才能给这个码下发任务；
#   · 客户端只执行**白名单动作**（不是任意 shell），而且**每一步都要用户在手机上点确认**；
#   · 所有动作与结果都写服务端审计。
SESS_DIR = os.path.join(DATA_DIR, "diag", "sessions")   # 注意：不能用 DIAG_DIR（此时它还没定义）
DIAG_TTL = 8 * 3600          # 求助码有效期 8 小时（用户可随时手动关闭；不再 30 分钟就掐断）
DIAG_MAX_TASKS = 20          # 单个会话最多下发 20 条任务


def _op_key():
    """运维密钥：首次运行生成，存在 data/diag/.opkey（600），只给服务器侧用"""
    p = os.path.join(DIAG_DIR, ".opkey")
    try:
        if os.path.isfile(p):
            with open(p, encoding="utf-8") as f:
                k = f.read().strip()
            if k:
                return k
    except Exception:
        pass
    os.makedirs(DIAG_DIR, exist_ok=True)
    k = secrets.token_urlsafe(18)
    try:
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(k)
    except Exception:
        pass
    return k


def _sess_path(code):
    safe = re.sub(r"[^A-Z0-9]", "", (code or "").upper())[:8]
    return os.path.join(SESS_DIR, safe + ".json") if safe else None


def _sess_load(code):
    p = _sess_path(code)
    if not p or not os.path.isfile(p):
        return None
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _sess_save(code, obj):
    p = _sess_path(code)
    if not p:
        return
    os.makedirs(SESS_DIR, exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, p)


@app.post("/api/diag/session")
def diag_session():
    """客户端开启求助会话：返回 6 位求助码（30 分钟有效）"""
    # 求助会话本身有 30 分钟 TTL，这里只防滥用；给足额度（手机反复点也不至于被锁）
    if not rate_limit("diagsess:" + client_ip(), limit=80, window=3600):
        return bad("开启太频繁（同一网络每小时最多 80 次），过一会儿再试", 429)
    code = _diag_code()
    now = int(time.time())
    obj = {
        "code": code,
        "created": now,
        "expires": now + DIAG_TTL,
        "ip": client_ip(),
        "app": clean(request.headers.get("X-App-Version", ""), 24),
        "patch": clean(request.headers.get("X-Patch-Version", ""), 24),
        "device": clean(request.headers.get("X-Device", ""), 120),
        "tasks": [],
        "results": [],
    }
    _sess_save(code, obj)
    audit("diag_session", "open", obj.get("app") or "-", {"code": code})
    return jsonify({"ok": True, "code": code, "expires": obj["expires"]})


@app.post("/api/diag/task")
def diag_task_push():
    """运维下发一条诊断/修复任务（需要运维密钥）"""
    if request.headers.get("X-Diag-Op", "") != _op_key():
        return bad("运维密钥不对", 403)
    body = request.get_json(force=True, silent=True) or {}
    code = (body.get("code") or "").upper()
    action = clean(body.get("action"), 32)
    sess = _sess_load(code)
    if not sess:
        return bad("求助码不存在", 404)
    if int(time.time()) > sess.get("expires", 0):
        return bad("求助码已过期", 410)
    if len(sess.get("tasks") or []) >= DIAG_MAX_TASKS:
        return bad("这个会话的任务已满", 429)
    tid = len(sess["tasks"]) + 1
    sess["tasks"].append({
        "id": tid, "action": action, "params": body.get("params") or {},
        "ts": int(time.time()), "state": "pending",
    })
    _sess_save(code, sess)
    audit("diag_task", "push", sess.get("app") or "-", {"code": code, "action": action})
    return jsonify({"ok": True, "id": tid})


@app.get("/api/diag/task")
def diag_task_pull():
    """客户端拉取待执行任务（每次只给一条）"""
    code = (request.args.get("code") or "").upper()
    sess = _sess_load(code)
    if not sess:
        return bad("求助码不存在", 404)
    if int(time.time()) > sess.get("expires", 0):
        return jsonify({"none": True, "expired": True})
    for t in sess.get("tasks") or []:
        if t.get("state") == "pending":
            t["state"] = "sent"
            _sess_save(code, sess)
            return jsonify({"none": False, "task": t})
    return jsonify({"none": True})


@app.post("/api/diag/result")
def diag_result():
    """客户端回传执行结果"""
    body = request.get_json(force=True, silent=True) or {}
    code = (body.get("code") or "").upper()
    sess = _sess_load(code)
    if not sess:
        return bad("求助码不存在", 404)
    tid = body.get("id")
    for t in sess.get("tasks") or []:
        if t.get("id") == tid:
            t["state"] = "done" if body.get("ok") else "failed"
    sess.setdefault("results", []).append({
        "id": tid, "ok": bool(body.get("ok")),
        "output": clean(body.get("output"), 4000), "ts": int(time.time()),
    })
    _sess_save(code, sess)
    audit("diag_result", "ok" if body.get("ok") else "fail", sess.get("app") or "-",
          {"code": code, "id": tid})
    return jsonify({"ok": True})


@app.get("/api/diag/session")
def diag_session_view():
    """运维查看会话与结果（需要运维密钥）"""
    if request.headers.get("X-Diag-Op", "") != _op_key():
        return bad("运维密钥不对", 403)
    sess = _sess_load((request.args.get("code") or "").upper())
    if not sess:
        return bad("求助码不存在", 404)
    return jsonify(sess)


# ---------------- 客户端诊断包（「请服务器协助排查」） ----------------
DIAG_DIR = os.path.join(DATA_DIR, "diag")
MAX_DIAG = 3 * 1024 * 1024        # 单个诊断包上限 3MB（客户端会做截断）
DIAG_KEEP = 200                   # 只保留最近 200 份，防磁盘被灌满


def _diag_code():
    """6 位求助码：不含容易看错的 0/O/1/I"""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(6))


@app.post("/api/diag/upload")
def diag_upload():
    """客户端一键上传日志（明文文本包）。不需要登录：靠限流 + 体积上限兜住。
    运维在服务器 data/diag/ 下按时间+求助码直接取文件看。"""
    if not rate_limit("diag:" + client_ip(), limit=10, window=3600):
        return bad("上传太频繁，过一会儿再试", 429)
    raw = request.get_data(cache=False) or b""
    if not raw:
        return bad("内容为空")
    if len(raw) > MAX_DIAG:
        return bad("诊断包太大（上限 3MB）", 413)
    os.makedirs(DIAG_DIR, exist_ok=True)
    code = _diag_code()
    name = "%s-%s.txt" % (time.strftime("%Y%m%d-%H%M%S"), code)
    meta = {
        "code": code,
        "file": name,
        "bytes": len(raw),
        "ts": int(time.time()),
        "ip": client_ip(),
        "app": clean(request.headers.get("X-App-Version", ""), 24),
        "patch": clean(request.headers.get("X-Patch-Version", ""), 24),
        "device": clean(request.headers.get("X-Device", ""), 120),
    }
    try:
        with open(os.path.join(DIAG_DIR, name), "wb") as f:
            f.write(raw)
        with open(os.path.join(DIAG_DIR, "index.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(meta, ensure_ascii=False) + "\n")
    except Exception as e:
        return bad("落盘失败：%s" % e, 500)
    try:                                    # 超出保留数量的老包删掉
        olds = sorted(x for x in os.listdir(DIAG_DIR) if x.endswith(".txt"))
        for x in olds[:-DIAG_KEEP]:
            os.remove(os.path.join(DIAG_DIR, x))
    except Exception:
        pass
    audit("diag", "ok", meta.get("app") or "-", {"code": code, "bytes": len(raw)})
    return jsonify({"ok": True, "code": code, "file": name})



# ---------------- 文件 ----------------

@app.post("/api/files")
@auth_required
def upload_file():
    to_type = request.args.get("to_type", "user")
    to_id = clean(request.args.get("to_id"), 64)
    name = clean(request.args.get("name", "file"), 120) or "file"
    if to_type not in ("user", "group") or not to_id:
        return bad("参数不全")
    # 先校验接收方存在，避免写出孤儿文件
    if to_type == "user":
        if not db().execute("SELECT 1 FROM users WHERE wxid=?", (to_id,)).fetchone():
            return bad("接收方不存在", 404)
    else:
        grp = db().execute("SELECT members FROM groups WHERE gid=?", (to_id,)).fetchone()
        if not grp or g.wxid not in json.loads(grp["members"]):
            return bad("群不存在或你不在群里", 403)

    data = request.get_data(cache=False, as_text=False)
    if not data:
        return bad("空文件")
    if len(data) > MAX_FILE:
        return jsonify({"error": "文件太大（上限 50MB）"}), 413

    fid = secrets.token_hex(16)
    os.makedirs(FILES_DIR, exist_ok=True)
    path = os.path.join(FILES_DIR, fid)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)                      # 原子落盘
    db().execute("INSERT INTO files(fid,owner,to_type,to_id,name,size,created)"
                 " VALUES(?,?,?,?,?,?,?)",
                 (fid, g.wxid, to_type, to_id, name, len(data), int(time.time())))
    db().commit()
    audit("upload_file", "ok", g.wxid, {"size": len(data)})
    return jsonify({"ok": True, "fid": fid, "size": len(data)})


@app.get("/api/files/<fid>")
@auth_required
def download_file(fid):
    if not re.match(r"^[a-f0-9]{32}$", fid or ""):
        return bad("文件不存在", 404)
    row = db().execute("SELECT * FROM files WHERE fid=?", (fid,)).fetchone()
    if not row:
        return bad("文件不存在", 404)
    allowed = row["owner"] == g.wxid
    if not allowed and row["to_type"] == "user":
        allowed = row["to_id"] == g.wxid
    if not allowed and row["to_type"] == "group":
        grp = db().execute("SELECT members FROM groups WHERE gid=?",
                           (row["to_id"],)).fetchone()
        allowed = bool(grp) and g.wxid in json.loads(grp["members"])
    if not allowed:
        audit("download_file", "denied", g.wxid, {"fid": fid})
        return bad("无权访问", 403)
    return send_from_directory(FILES_DIR, fid, as_attachment=True,
                               download_name=row["name"] or "file")


# ---------------- 账号 ----------------

@app.post("/api/register")
def register():
    # 注册限流：同一 IP 每小时 20 个（v0.0.6 由 5 放宽到 20 —— 本机自测/一家人换设备
    # 常常连开几个号，5 个太紧；真正的爆破防守靠下面的登录锁定 + 失败计数）
    if not rate_limit("reg:" + client_ip(), limit=20, window=3600):
        return jsonify({"error": "注册过于频繁"}), 429

    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    nickname = clean(b.get("nickname"), 32) or wxid
    password = b.get("password") or ""
    pubkey = b.get("pubkey") or ""
    privkey_enc = b.get("privkey_enc") or ""
    kdf_salt = b.get("kdf_salt") or ""
    phone = _norm_phone(b.get("phone") or "")
    email = _norm_email(b.get("email") or "")

    if not RE_WXID.match(wxid):
        return bad("玲珑号只能 3-32 位字母、数字、下划线")
    if len(password) < 6 or len(password) > 128:
        return bad("密码需 6-128 位")
    if not phone and not email:
        return bad("手机号或邮箱至少填一个")
    if phone and not re.match(r"^\+?[0-9]{6,20}$", phone):
        return bad("手机号格式不正确")
    if email and not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email):
        return bad("邮箱格式不正确")
    for name, v in (("公钥", pubkey), ("私钥密文", privkey_enc), ("盐", kdf_salt)):
        if not v:
            return bad("缺少" + name)
    if len(pubkey) > 4096 or len(privkey_enc) > 4096:
        return bad("密钥格式不正确")

    phone_hash = _hash_contact("p", phone) if phone else None
    email_hash = _hash_contact("e", email) if email else None
    if phone_hash and db().execute("SELECT 1 FROM users WHERE phone_hash=?", (phone_hash,)).fetchone():
        return bad("这个手机号已经注册过了", 409)
    if email_hash and db().execute("SELECT 1 FROM users WHERE email_hash=?", (email_hash,)).fetchone():
        return bad("这个邮箱已经注册过了", 409)

    pw_hash, pw_salt = hash_pw(password)
    try:
        db().execute(
            "INSERT INTO users(wxid,nickname,pubkey,privkey_enc,kdf_salt,pw_hash,pw_salt,created,"
            "phone_hash,email_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (wxid, nickname, pubkey, privkey_enc, kdf_salt, pw_hash, pw_salt,
             int(time.time()), phone_hash, email_hash))
        db().commit()
    except sqlite3.IntegrityError:
        return bad("这个玲珑号已经被用了", 409)
    audit("register", "ok", wxid)
    return jsonify({"ok": True, "wxid": wxid})


@app.post("/api/login")
def login():
    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    password = b.get("password") or ""
    if not wxid or not password:
        return bad("参数不全")

    # 失败锁定：按「专属号 + IP」和「仅专属号」双维度（换 IP 也绕不过账号锁）
    key = wxid + "|" + client_ip()
    st = _LOGIN_FAILS.get(key)
    if st and st[1] > time.time():
        audit("login", "locked", wxid)
        return jsonify({"error": "密码错误次数过多，请 %d 秒后再试"
                        % int(st[1] - time.time())}), 429
    ast = _LOGIN_FAILS_ACCT.get(wxid)
    if ast and ast[1] > time.time():
        audit("login", "locked_acct", wxid)
        return jsonify({"error": "这个账号密码错误次数过多，请 %d 秒后再试"
                        % int(ast[1] - time.time())}), 429

    row = db().execute("SELECT * FROM users WHERE wxid=?", (wxid,)).fetchone()
    if not row or not check_pw(password, row["pw_salt"], row["pw_hash"]):
        st = _LOGIN_FAILS.setdefault(key, [0, 0])
        st[0] += 1
        if st[0] >= LOGIN_MAX_FAILS:
            st[0] = 0
            st[1] = time.time() + LOGIN_LOCK_SECONDS
        ast = _LOGIN_FAILS_ACCT.setdefault(wxid, [0, 0])
        ast[0] += 1
        if ast[0] >= LOGIN_MAX_FAILS:
            ast[0] = 0
            ast[1] = time.time() + LOGIN_LOCK_SECONDS
        audit("login", "fail", wxid)
        return jsonify({"error": "专属号或密码不对"}), 401

    _LOGIN_FAILS.pop(key, None)
    _LOGIN_FAILS_ACCT.pop(wxid, None)
    db().execute("DELETE FROM tokens WHERE created < ?",
                 (int(time.time()) - TOKEN_TTL,))          # 清过期 token
    token = secrets.token_hex(32)
    db().execute("INSERT INTO tokens(token,wxid,created) VALUES(?,?,?)",
                 (_tok_hash(token), wxid, int(time.time())))     # 库里只存哈希
    db().commit()
    audit("login", "ok", wxid)
    return jsonify({"ok": True, "token": token, "wxid": wxid,
                    "nickname": row["nickname"], "pubkey": row["pubkey"],
                    "pubkey_ver": row["pubkey_ver"] if "pubkey_ver" in row.keys() else 1,
                    "avatar_ver": row["avatar_ver"] if "avatar_ver" in row.keys() else 0,
                    "privkey_enc": row["privkey_enc"], "kdf_salt": row["kdf_salt"]})


@app.post("/api/logout")
@auth_required
def logout():
    db().execute("DELETE FROM tokens WHERE token=?", (g.token,))
    db().commit()
    audit("logout", "ok", g.wxid)
    return jsonify({"ok": True})


@app.get("/api/me")
@auth_required
def me():
    row = db().execute("SELECT wxid,nickname,pubkey FROM users WHERE wxid=?",
                       (g.wxid,)).fetchone()
    return jsonify(dict(row))


@app.get("/api/pubkeys")
@auth_required
def pubkeys():
    """批量取「当前」公钥（含版本号）——好友换过密钥后靠它自动同步。

    只要还是好友就一定能聊：客户端发送前 / 解密失败时调一次这里，
    拿到的永远是最新公钥，不用再人工重新加好友。
    """
    ids = [x for x in clean(request.args.get("ids", ""), 1024).split(",")
           if RE_WXID.match(x)]
    ids = list(dict.fromkeys(ids))[:50]
    if not ids:
        return jsonify({"users": {}})
    q = ("SELECT wxid,nickname,pubkey,pubkey_ver FROM users WHERE wxid IN (%s)"
         % ",".join("?" * len(ids)))
    rows = db().execute(q, ids).fetchall()
    return jsonify({"users": {r["wxid"]: {"nickname": r["nickname"],
                                          "pubkey": r["pubkey"],
                                          "ver": r["pubkey_ver"]} for r in rows}})


@app.post("/api/profile")
@auth_required
def update_profile():
    """改昵称 / 换头像（头像存文件，avatar_ver 自增让好友端缓存失效）"""
    b = request.get_json(force=True, silent=True) or {}
    nick = clean(b.get("nickname"), 32)
    av = b.get("avatar") or ""
    if nick:
        db().execute("UPDATE users SET nickname=? WHERE wxid=?", (nick, g.wxid))
    if av:
        try:
            raw = base64.b64decode(av, validate=False)
        except Exception:
            return bad("头像编码不正确")
        if len(raw) > MAX_AVATAR:
            return bad("头像太大（最多 400KB）", 413)
        if raw[:8] == b"\x89PNG\r\n\x1a\n":
            ext = ".png"
        elif raw[:3] == b"\xff\xd8\xff":
            ext = ".jpg"
        else:
            return bad("头像只支持 PNG / JPEG")
        os.makedirs(AVATAR_DIR, exist_ok=True)
        for e in (".png", ".jpg"):
            p = os.path.join(AVATAR_DIR, g.wxid + e)
            if e != ext and os.path.exists(p):
                os.remove(p)
        with open(os.path.join(AVATAR_DIR, g.wxid + ext), "wb") as f:
            f.write(raw)
        db().execute("UPDATE users SET avatar_ver=avatar_ver+1 WHERE wxid=?", (g.wxid,))
    db().commit()
    row = db().execute("SELECT nickname, avatar_ver FROM users WHERE wxid=?",
                       (g.wxid,)).fetchone()
    audit("profile", "ok", g.wxid, {"nick": bool(nick), "avatar": bool(av)})
    return jsonify({"ok": True, "nickname": row["nickname"], "avatar_ver": row["avatar_ver"]})


@app.get("/api/avatar/<wxid>")
@auth_required
def get_avatar(wxid):
    if not RE_WXID.match(wxid):
        return bad("参数不对")
    for ext, mt in ((".png", "image/png"), (".jpg", "image/jpeg")):
        p = os.path.join(AVATAR_DIR, wxid + ext)
        if os.path.exists(p):
            return send_file(p, mimetype=mt)
    return bad("还没有头像", 404)


@app.post("/api/friends")
@auth_required
def add_friend():
    """加好友 = 发好友申请（微信式）：对方通过后双方才进通讯录。

    以前是「我一点，服务端就把我也塞进对方通讯录（双向写）」——谁都能把陌生人
    加进来，对方下一批朋友圈的密钥就会包给他，还能反过来把对方从别人通讯录删掉。
    现在只写自己这一行（state=0），对方通过才置 1。
    """
    b = request.get_json(force=True, silent=True) or {}
    target = clean(b.get("wxid"), 32)
    if not RE_WXID.match(target) or target == g.wxid:
        return bad("专属号格式不正确")
    if not db().execute("SELECT 1 FROM users WHERE wxid=?", (target,)).fetchone():
        return bad("找不到这个人", 404)
    now = int(time.time())
    mine = db().execute("SELECT state FROM friends WHERE owner=? AND friend=?",
                        (g.wxid, target)).fetchone()
    if mine and int(mine["state"] or 0) == 1:
        return jsonify({"ok": True, "state": "friends", "already": True})
    rev = db().execute("SELECT state FROM friends WHERE owner=? AND friend=?",
                       (target, g.wxid)).fetchone()
    if rev and int(rev["state"] or 0) == 0:      # 对方也申请过我 → 直接互相通过
        db().execute("UPDATE friends SET state=1 WHERE owner=? AND friend=?",
                     (target, g.wxid))
        if mine:
            db().execute("UPDATE friends SET state=1 WHERE owner=? AND friend=?",
                         (g.wxid, target))
        else:
            db().execute("INSERT INTO friends(owner,friend,created,state) VALUES(?,?,?,1)",
                         (g.wxid, target, now))
        db().commit()
        audit("friend_mutual", "ok", g.wxid, {"target": target})
        return jsonify({"ok": True, "state": "friends"})
    if mine:                                      # 已申请过：幂等，只刷新时间
        db().execute("UPDATE friends SET created=? WHERE owner=? AND friend=?",
                     (now, g.wxid, target))
    else:
        db().execute("INSERT INTO friends(owner,friend,created,state) VALUES(?,?,?,0)",
                     (g.wxid, target, now))
    db().commit()
    audit("friend_request", "ok", g.wxid, {"target": target})
    return jsonify({"ok": True, "state": "pending"})


@app.get("/api/friends")
@auth_required
def list_friends():
    rows = db().execute(
        "SELECT u.wxid,u.nickname,u.pubkey,u.pubkey_ver,u.avatar_ver FROM friends f "
        "JOIN users u ON u.wxid=f.friend WHERE f.owner=? AND f.state=1 ORDER BY u.nickname",
        (g.wxid,)).fetchall()
    return jsonify({"friends": [dict(r) for r in rows]})


@app.get("/api/friends/requests")
@auth_required
def friend_requests():
    """好友申请：待我处理 + 我发出去的"""
    incoming = db().execute(
        "SELECT u.wxid,u.nickname,u.pubkey,u.pubkey_ver,u.avatar_ver,f.created FROM friends f "
        "JOIN users u ON u.wxid=f.owner WHERE f.friend=? AND f.state=0 ORDER BY f.created DESC",
        (g.wxid,)).fetchall()
    outgoing = db().execute(
        "SELECT u.wxid,u.nickname,u.pubkey,u.pubkey_ver,u.avatar_ver,f.created FROM friends f "
        "JOIN users u ON u.wxid=f.friend WHERE f.owner=? AND f.state=0 ORDER BY f.created DESC",
        (g.wxid,)).fetchall()
    return jsonify({"incoming": [dict(r) for r in incoming],
                    "outgoing": [dict(r) for r in outgoing]})


@app.post("/api/friends/accept")
@auth_required
def friend_accept():
    """通过对方的好友申请：两边都置为好友"""
    b = request.get_json(force=True, silent=True) or {}
    wx = clean(b.get("wxid"), 32)
    if not RE_WXID.match(wx or ""):
        return bad("参数不对")
    row = db().execute("SELECT 1 FROM friends WHERE owner=? AND friend=? AND state=0",
                       (wx, g.wxid)).fetchone()
    if not row:
        return bad("没有这条好友申请", 404)
    now = int(time.time())
    db().execute("UPDATE friends SET state=1 WHERE owner=? AND friend=?", (wx, g.wxid))
    mine = db().execute("SELECT 1 FROM friends WHERE owner=? AND friend=?",
                        (g.wxid, wx)).fetchone()
    if mine:
        db().execute("UPDATE friends SET state=1 WHERE owner=? AND friend=?", (g.wxid, wx))
    else:
        db().execute("INSERT INTO friends(owner,friend,created,state) VALUES(?,?,?,1)",
                     (g.wxid, wx, now))
    db().commit()
    audit("friend_accept", "ok", g.wxid, {"target": wx})
    return jsonify({"ok": True})


@app.post("/api/friends/reject")
@auth_required
def friend_reject():
    """拒绝好友申请（只删对方发来的那条）"""
    b = request.get_json(force=True, silent=True) or {}
    wx = clean(b.get("wxid"), 32)
    if not RE_WXID.match(wx or ""):
        return bad("参数不对")
    db().execute("DELETE FROM friends WHERE owner=? AND friend=? AND state=0",
                 (wx, g.wxid))
    db().commit()
    audit("friend_reject", "ok", g.wxid, {"target": wx})
    return jsonify({"ok": True})


def _remove_friend_both(wxid):
    """彻底删除：双方通讯录 + 双方的待处理申请一起清掉"""
    db().execute("DELETE FROM friends WHERE (owner=? AND friend=?) OR (owner=? AND friend=?)",
                 (g.wxid, wxid, wxid, g.wxid))
    db().commit()


@app.delete("/api/friends/<wxid>")
@auth_required
def del_friend(wxid):
    """删好友 = 彻底删除：两边一起移除（连带双方的待处理申请）"""
    _remove_friend_both(wxid)
    audit("del_friend", "ok", g.wxid, {"target": wxid, "via": "delete"})
    return jsonify({"ok": True})


@app.post("/api/friends/remove")
@auth_required
def friend_remove_post():
    """删好友（POST 版，等价于 DELETE /api/friends/<wxid>）。

    为什么要这个：服务端日志显示从没收到过 DELETE 请求（可能被机型/安全软件吞了），
    POST + JSON 更稳；客户端先 POST，失败再退回 DELETE。
    """
    b = request.get_json(force=True, silent=True) or {}
    wx = clean(b.get("wxid"), 32)
    if not RE_WXID.match(wx or ""):
        return bad("参数不对")
    _remove_friend_both(wx)
    audit("del_friend", "ok", g.wxid, {"target": wx, "via": "post"})
    return jsonify({"ok": True})


@app.get("/api/search")
@auth_required
def search():
    q = clean(request.args.get("q"), 32)
    if not q:
        return jsonify({"users": []})
    rows = db().execute(
        "SELECT wxid,nickname,pubkey FROM users WHERE wxid LIKE ? ESCAPE '\\' "
        "AND wxid != ? LIMIT 10",
        (_like_lit(q) + "%", g.wxid)).fetchall()
    return jsonify({"users": [dict(r) for r in rows]})


# ---------------- 找回 ----------------

REC_TTL = 1800          # 救援密钥有效期 30 分钟


def _key128():
    """生成 128 位救援密钥：16 字节随机 → 32 位十六进制，按 4 位分组"""
    raw = secrets.token_hex(16).upper()
    return "-".join(raw[i:i + 4] for i in range(0, 32, 4))


def _norm_key(k):
    return re.sub(r"[^A-F0-9]", "", (k or "").upper())


@app.post("/api/recovery/apply")
def recovery_apply():
    """① 忘记密码的人：申请一个 128 位救援密钥（屏幕上显示，抄给好友）"""
    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    if not RE_WXID.match(wxid):
        return bad("玲珑号格式不正确")
    row = db().execute("SELECT * FROM users WHERE wxid=?", (wxid,)).fetchone()
    if not row:
        return bad("该账号不存在", 404)

    contact = (b.get("contact") or "").strip()
    if not contact:
        return bad("请输入注册时填写的手机号或邮箱")
    if "@" in contact:
        h = _hash_contact("e", _norm_email(contact))
        ok = bool(row["email_hash"]) and hmac.compare_digest(row["email_hash"], h)
    else:
        h = _hash_contact("p", _norm_phone(contact))
        ok = bool(row["phone_hash"]) and hmac.compare_digest(row["phone_hash"], h)
    if not ok:
        audit("recovery_apply", "contact_fail", wxid)
        return bad("手机号/邮箱和这个账号不匹配", 401)

    key = _key128()
    db().execute("INSERT OR REPLACE INTO recover_codes(wxid,key128,approved_by,expires,used)"
                 " VALUES(?,?,?,?,0)",
                 (wxid, _norm_key(key), "", int(time.time()) + REC_TTL))
    db().commit()
    audit("recovery_apply", "ok", wxid)
    return jsonify({"ok": True, "key128": key, "expires_in": REC_TTL,
                    "note": "把这串密钥发给一位好友，请他在自己的 App 里输入批准"})


@app.post("/api/recovery/approve")
@auth_required
def recovery_approve():
    """② 好友：在 App 里输入对方的专属号 + 这串 128 位密钥，批准这次找回"""
    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    key = _norm_key(b.get("key128"))
    if not wxid or len(key) != 32:
        return bad("参数不全")
    if wxid == g.wxid:
        return bad("不能给自己批准，请找另一位好友")
    row = db().execute("SELECT * FROM recover_codes WHERE wxid=?", (wxid,)).fetchone()
    if not row:
        return bad("该账号没有发起找回申请", 404)
    if row["used"]:
        return bad("这次找回已经用过了", 409)
    if row["expires"] < time.time():
        return bad("密钥已过期，请让对方重新申请", 410)
    if row["approved_by"]:
        return jsonify({"ok": True, "already": True, "approved_by": row["approved_by"]})
    if not hmac.compare_digest(row["key128"], key):
        audit("recovery_approve", "fail", g.wxid, {"target": wxid})
        return bad("密钥不正确", 401)

    db().execute("UPDATE recover_codes SET approved_by=? WHERE wxid=?", (g.wxid, wxid))
    db().commit()
    audit("recovery_approve", "ok", g.wxid, {"target": wxid})
    return jsonify({"ok": True, "approved": wxid})


@app.post("/api/recovery/check")
def recovery_check():
    """③ 找回人：看看好友批准了没有"""
    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    key = _norm_key(b.get("key128"))
    row = db().execute("SELECT * FROM recover_codes WHERE wxid=?", (wxid,)).fetchone()
    if not row or not hmac.compare_digest(row["key128"], key):
        return bad("密钥不正确", 401)
    if row["expires"] < time.time():
        return bad("密钥已过期，请重新申请", 410)
    return jsonify({"ok": True, "approved": bool(row["approved_by"]),
                    "approved_by": row["approved_by"] or ""})


@app.post("/api/recovery/finish")
def recovery_finish():
    """④ 找回人：好友批准后设置新密码（用本机私钥重新加密；本机没有则换新密钥）"""
    b = request.get_json(force=True, silent=True) or {}
    wxid = clean(b.get("wxid"), 32)
    key = _norm_key(b.get("key128"))
    password = b.get("password") or ""
    privkey_enc = b.get("privkey_enc") or ""
    kdf_salt = clean(b.get("kdf_salt"), 64)
    pubkey = b.get("pubkey") or ""
    if not wxid or not (6 <= len(password) <= 128) or not privkey_enc or not kdf_salt:
        return bad("参数不全")

    row = db().execute("SELECT * FROM recover_codes WHERE wxid=?", (wxid,)).fetchone()
    if not row:
        return bad("没有找到这次找回申请", 404)
    if row["used"]:
        return bad("这次找回已经用过了", 409)
    if row["expires"] < time.time():
        return bad("密钥已过期，请重新申请", 410)
    if not row["approved_by"]:
        return bad("还没有好友批准这次找回", 403)
    if not hmac.compare_digest(row["key128"], key):
        return bad("密钥不正确", 401)

    pw_hash, pw_salt = hash_pw(password)
    db().execute("UPDATE users SET pw_hash=?, pw_salt=?, privkey_enc=?, kdf_salt=? WHERE wxid=?",
                 (pw_hash, pw_salt, privkey_enc, kdf_salt, wxid))
    if pubkey:                                    # 本机没有旧私钥时，客户端会生成新密钥对
        db().execute("UPDATE users SET pubkey=?, pubkey_ver=pubkey_ver+1 WHERE wxid=?",
                     (pubkey, wxid))
    db().execute("UPDATE recover_codes SET used=1 WHERE wxid=?", (wxid,))
    db().execute("DELETE FROM tokens WHERE wxid=?", (wxid,))     # 旧会话全部作废
    db().commit()
    audit("recovery_finish", "ok", wxid, {"new_keypair": bool(pubkey)})
    return jsonify({"ok": True, "new_keypair": bool(pubkey)})


# ---------------- 消息 ----------------

@app.post("/api/messages")
@auth_required
def send_message():
    b = request.get_json(force=True, silent=True) or {}
    to_type = b.get("to_type")
    to_id = clean(b.get("to_id"), 64)
    payload = b.get("payload") or ""
    sig = b.get("sig") or ""
    ts = b.get("ts")
    if to_type not in ("user", "group") or not to_id or not payload:
        return bad("参数不全")
    if len(payload) > MAX_PAYLOAD:
        return bad("消息过大", 413)
    if len(sig) > 512:
        return bad("签名格式不正确")

    if to_type == "user":
        if not db().execute("SELECT 1 FROM users WHERE wxid=?", (to_id,)).fetchone():
            return bad("对方不存在", 404)
    else:
        grp = db().execute("SELECT members FROM groups WHERE gid=?", (to_id,)).fetchone()
        if not grp:
            return bad("群不存在", 404)
        if g.wxid not in json.loads(grp["members"]):
            return bad("你不在这个群里", 403)

    cur = db().execute(
        "INSERT INTO messages(to_type,to_id,from_wxid,payload,sig,ts) VALUES(?,?,?,?,?,?)",
        (to_type, to_id, g.wxid, payload, sig,
         int(ts) if isinstance(ts, int) else int(time.time() * 1000)))
    db().commit()
    return jsonify({"ok": True, "id": cur.lastrowid})


@app.post("/api/messages/<int:mid>/recall")
@auth_required
def recall_message(mid):
    """撤回：只有发件人本人、2 分钟内可撤。撤回后对方只看到「撤回了一条消息」。"""
    row = db().execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
    if not row:
        return bad("消息不存在", 404)
    if row["from_wxid"] != g.wxid:
        return bad("只能撤回自己发的消息", 403)
    if row["recalled"]:
        return jsonify({"ok": True, "already": True})
    if int(time.time() * 1000) - int(row["ts"]) > 120000:
        return bad("超过 2 分钟，不能撤回了", 400)
    db().execute("UPDATE messages SET recalled=1 WHERE id=?", (mid,))
    db().commit()
    audit("recall", "ok", g.wxid, {"id": mid})
    return jsonify({"ok": True})


@app.get("/api/messages")
@auth_required
def fetch_messages():
    """拉取「发给我的 + 我发出的单聊」+「我所在群的消息」；wait>0 时长轮询"""
    try:
        since = int(request.args.get("since", "0") or 0)
    except ValueError:
        since = 0
    try:
        wait = max(0, min(int(request.args.get("wait", "0") or 0), 30))
    except ValueError:
        wait = 0

    my_groups = [r["gid"] for r in db().execute(
        "SELECT gid FROM groups WHERE members LIKE ? ESCAPE '\\'",
        ('%"' + _like_lit(g.wxid) + '"%',)).fetchall()]

    if my_groups:
        sql = ("SELECT * FROM messages WHERE id > ? AND ("
               "  (to_type='user' AND (to_id=? OR from_wxid=?)) OR "
               "  (to_type='group' AND to_id IN (%s))"
               ") ORDER BY id ASC LIMIT 500") % ",".join("?" * len(my_groups))
        args = [since, g.wxid, g.wxid] + my_groups
    else:
        sql = ("SELECT * FROM messages WHERE id > ? AND "
               "(to_type='user' AND (to_id=? OR from_wxid=?))"
               " ORDER BY id ASC LIMIT 500")
        args = [since, g.wxid, g.wxid]

    deadline = time.time() + wait
    while True:
        rows = db().execute(sql, args).fetchall()
        if rows or time.time() >= deadline:
            break
        time.sleep(0.2)

    msgs = [{"id": r["id"], "to_type": r["to_type"], "to_id": r["to_id"],
             "from": r["from_wxid"], "payload": r["payload"],
             "sig": r["sig"], "ts": r["ts"],
             "recalled": int(r["recalled"] or 0)} for r in rows]
    return jsonify({"messages": msgs,
                    "cursor": msgs[-1]["id"] if msgs else since})


# ---------------- 群组 ----------------

@app.post("/api/groups")
@auth_required
def create_group():
    b = request.get_json(force=True, silent=True) or {}
    name = clean(b.get("name"), 40) or "群聊"
    members = b.get("members") or {}
    if not isinstance(members, dict) or not members:
        return bad("至少要有一个成员")
    if len(members) > 200:
        return bad("群成员过多")
    members[g.wxid] = members.get(g.wxid) or ""
    for wx in list(members.keys()):
        if not isinstance(wx, str) or not RE_WXID.match(wx):
            return bad("成员账号格式不正确")
        if not db().execute("SELECT 1 FROM users WHERE wxid=?", (wx,)).fetchone():
            return bad("成员不存在: " + wx[:32], 404)
        if wx != g.wxid and not _mutual_friends(wx):
            return bad("只能把好友拉进群：" + wx[:32], 403)
        if len(members[wx]) > 4096:
            return bad("群密钥格式不正确")

    gid = "g" + secrets.token_hex(8)
    db().execute("INSERT INTO groups(gid,name,owner,members,created) VALUES(?,?,?,?,?)",
                 (gid, name, g.wxid, json.dumps(sorted(members.keys())), int(time.time())))
    for wx, wrapped in members.items():
        db().execute("INSERT INTO group_keys(gid,wxid,wrapped,wrapped_by,ver)"
                     " VALUES(?,?,?,?,?)", (gid, wx, wrapped, g.wxid, _pubkey_ver(wx)))
    db().commit()
    audit("create_group", "ok", g.wxid, {"gid": gid, "n": len(members)})
    return jsonify({"ok": True, "gid": gid})


@app.get("/api/groups")
@auth_required
def my_groups():
    rows = db().execute(
        "SELECT g.*, k.wrapped, k.wrapped_by FROM groups g LEFT JOIN group_keys k "
        "ON g.gid=k.gid AND k.wxid=? WHERE g.members LIKE ? ESCAPE '\\'",
        (g.wxid, '%"' + _like_lit(g.wxid) + '"%')).fetchall()
    groups = []
    everyone = set()
    for r in rows:
        members = json.loads(r["members"])
        everyone.update(members)
        groups.append({
            "gid": r["gid"], "name": r["name"], "owner": r["owner"],
            "members": members,
            "admins": json.loads(r["admins"] or "[]"),
            "name_admin_only": int(r["name_admin_only"] or 0),
            "notice": r["notice"] or "",
            "allow_member_invite": int(r["allow_member_invite"] if r["allow_member_invite"] is not None else 1),
            "my_wrapped": r["wrapped"],
            "my_wrapped_by": r["wrapped_by"] or r["owner"],
        })
    # 成员资料（昵称/头像版本）—— 群里可能不是好友，客户端本地没有他们的资料
    profiles = {}
    if everyone:
        q = ",".join("?" * len(everyone))
        for u in db().execute(
                "SELECT wxid,nickname,avatar_ver FROM users WHERE wxid IN (%s)" % q,
                tuple(everyone)).fetchall():
            profiles[u["wxid"]] = {"wxid": u["wxid"], "nickname": u["nickname"],
                                   "avatar_ver": u["avatar_ver"]}
    return jsonify({"groups": groups, "profiles": profiles})


@app.post("/api/groups/<gid>/rewrap")
@auth_required
def group_rewrap(gid):
    """成员换了密钥后，任何「手上还有群密钥」的成员都可以替他把群密钥重新包裹一份。

    这就是「只要还是好友/群友就一直能聊」的关键：换钥的一方自己解不开，
    但其他成员解得开，顺手补一份给新公钥即可。
    """
    b = request.get_json(force=True, silent=True) or {}
    member = clean(b.get("member"), 32)
    wrapped = b.get("wrapped") or ""
    if not RE_WXID.match(member or "") or not wrapped:
        return bad("参数不全")
    if len(wrapped) > 4096:
        return bad("群密钥格式不正确")
    row = db().execute("SELECT * FROM groups WHERE gid=?", (gid,)).fetchone()
    if not row:
        return bad("群不存在", 404)
    members = json.loads(row["members"])
    if g.wxid not in members:
        return bad("你不在这个群里", 403)
    if member not in members:
        return bad("对方不在这个群里", 404)
    # 防"成员互相覆盖包裹把别人搞成解不开"：只有对方公钥版本前进过才允许补
    cur_ver = _pubkey_ver(member)
    stored = db().execute("SELECT ver FROM group_keys WHERE gid=? AND wxid=?",
                          (gid, member)).fetchone()
    is_admin = (g.wxid == row["owner"]) or (g.wxid in _group_admins(row))
    if stored and int(stored["ver"] or 0) >= cur_ver and not is_admin:
        return bad("对方密钥没有变化，不需要补发", 409)
    db().execute("INSERT OR REPLACE INTO group_keys(gid,wxid,wrapped,wrapped_by,ver)"
                 " VALUES(?,?,?,?,?)", (gid, member, wrapped, g.wxid, cur_ver))
    db().commit()
    audit("group_rewrap", "ok", g.wxid, {"gid": gid, "member": member})
    return jsonify({"ok": True})


@app.delete("/api/groups/<gid>/members/me")
@auth_required
def leave_group(gid):
    """退出群聊：把自己从 members 里摘掉、删掉自己那份群密钥。

    · 群主退群 → 顺位交给剩下最早加入的成员（不然群没人管）
    · 最后一个人退群 → 群直接解散（groups / group_keys 全清）
    · 退出后 /api/messages 按 members 过滤，自然收不到这个群的新消息
    """
    # ⚠️ 这里必须把 admins 一起查出来：下面要把它从管理员名单里摘掉（之前漏了 → 退群 500）
    row = db().execute("SELECT gid,name,owner,members,admins FROM groups WHERE gid=?", (gid,)).fetchone()
    if not row:
        return bad("群不存在", 404)
    members = json.loads(row["members"])
    if g.wxid not in members:
        return bad("你不在这个群里", 403)
    members = [m for m in members if m != g.wxid]
    admins = [a for a in json.loads(row["admins"] or "[]") if a != g.wxid]
    db().execute("DELETE FROM group_keys WHERE gid=? AND wxid=?", (gid, g.wxid))
    if not members:
        db().execute("DELETE FROM groups WHERE gid=?", (gid,))
        db().execute("DELETE FROM group_keys WHERE gid=?", (gid,))
        db().commit()
        audit("leave_group", "dissolved", g.wxid, {"gid": gid})
        return jsonify({"ok": True, "dissolved": True, "remaining": 0})
    owner = row["owner"]
    if owner == g.wxid:
        owner = members[0]
    db().execute("UPDATE groups SET members=?, owner=?, admins=? WHERE gid=?",
                 (json.dumps(sorted(members)), owner, json.dumps(admins), gid))
    db().commit()
    audit("leave_group", "ok", g.wxid, {"gid": gid, "remaining": len(members)})
    return jsonify({"ok": True, "dissolved": False, "remaining": len(members),
                    "owner": owner})


# ---------------- 群管理（改名 / 转让 / 管理员 / 设置 / 解散） ----------------

def _group_row(gid):
    return db().execute("SELECT * FROM groups WHERE gid=?", (gid,)).fetchone()


def _group_admins(row):
    try:
        return json.loads(row["admins"] or "[]")
    except Exception:
        return []


@app.post("/api/groups/<gid>/name")
@auth_required
def group_rename(gid):
    """改群名：默认谁都能改；开了「仅群主/管理员可改」后只认群主和管理员"""
    b = request.get_json(force=True, silent=True) or {}
    name = clean(b.get("name"), 40)
    if not name:
        return bad("群名称不能为空")
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    members = json.loads(row["members"])
    if g.wxid not in members:
        return bad("你不在这个群里", 403)
    if int(row["name_admin_only"] or 0) and g.wxid != row["owner"] and g.wxid not in _group_admins(row):
        return bad("只有群主或管理员可以修改群名称", 403)
    db().execute("UPDATE groups SET name=? WHERE gid=?", (name, gid))
    db().commit()
    audit("group_rename", "ok", g.wxid, {"gid": gid})
    return jsonify({"ok": True, "name": name})


@app.post("/api/groups/<gid>/owner")
@auth_required
def group_transfer(gid):
    """群主管理权转让：只有现任群主能操作，目标必须是群成员"""
    b = request.get_json(force=True, silent=True) or {}
    member = clean(b.get("member"), 32)
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if row["owner"] != g.wxid:
        return bad("只有群主可以转让群主", 403)
    members = json.loads(row["members"])
    if member not in members:
        return bad("对方不在这个群里", 404)
    if member == g.wxid:
        return bad("不能转给自己")
    db().execute("UPDATE groups SET owner=? WHERE gid=?", (member, gid))
    db().commit()
    audit("group_transfer", "ok", g.wxid, {"gid": gid, "to": member})
    return jsonify({"ok": True, "owner": member})


@app.post("/api/groups/<gid>/admins")
@auth_required
def group_set_admins(gid):
    """设置群管理员：整份覆盖，只允许群主操作，管理员必须是群成员（最多 10 人）"""
    b = request.get_json(force=True, silent=True) or {}
    want = b.get("members")
    if not isinstance(want, list):
        return bad("参数不正确")
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if row["owner"] != g.wxid:
        return bad("只有群主可以设置管理员", 403)
    members = json.loads(row["members"])
    admins = []
    for wx in want:
        if isinstance(wx, str) and wx in members and wx != row["owner"] and wx not in admins:
            admins.append(wx)
        if len(admins) >= 10:
            break
    db().execute("UPDATE groups SET admins=? WHERE gid=?", (json.dumps(admins), gid))
    db().commit()
    audit("group_admins", "ok", g.wxid, {"gid": gid, "n": len(admins)})
    return jsonify({"ok": True, "admins": admins})


@app.post("/api/groups/<gid>/qr")
@auth_required
def group_qr(gid):
    """生成群二维码的入场 token（7 天有效）。群成员才能生成。

    E2E 说明：群密钥不会经过服务端 —— 客户端用一次性口令把群密钥加密后写进二维码本身，
    这里只发一个随机 token 当作"入场券"，服务端只负责凭 token 把人加进群。
    """
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if g.wxid not in json.loads(row["members"]):
        return bad("你不在这个群里", 403)
    token = secrets.token_hex(12)
    now = int(time.time())
    exp = now + 7 * 86400
    db().execute("INSERT INTO group_invites(token,gid,created,expires) VALUES(?,?,?,?)",
                 (token, gid, now, exp))
    db().execute("DELETE FROM group_invites WHERE gid=? AND token NOT IN "
                 "(SELECT token FROM group_invites WHERE gid=? ORDER BY created DESC LIMIT 10)",
                 (gid, gid))
    db().commit()
    audit("group_qr", "ok", g.wxid, {"gid": gid})
    return jsonify({"ok": True, "token": token, "expires": exp})


@app.get("/api/groups/join/info")
@auth_required
def group_join_info():
    """扫码后先看一眼：是哪个群、几个人、自己是不是已经在里面"""
    token = clean(request.args.get("token"), 64)
    row = db().execute("SELECT * FROM group_invites WHERE token=?", (token,)).fetchone()
    if not row:
        return bad("二维码无效", 404)
    if int(row["expires"]) < int(time.time()):
        return bad("二维码已过期，请让群成员重新生成", 410)
    gr = _group_row(row["gid"])
    if not gr:
        return bad("群不存在", 404)
    members = json.loads(gr["members"])
    return jsonify({"ok": True, "gid": gr["gid"], "name": gr["name"],
                    "count": len(members), "is_member": g.wxid in members})


@app.post("/api/groups/join")
@auth_required
def group_join():
    """凭二维码 token 入群：加进 members，并把我那份（二维码里的）密钥包裹存下来"""
    b = request.get_json(force=True, silent=True) or {}
    token = clean(b.get("token"), 64)
    wrapped = b.get("wrapped") or ""
    row = db().execute("SELECT * FROM group_invites WHERE token=?", (token,)).fetchone()
    if not row:
        return bad("二维码无效", 404)
    if int(row["expires"]) < int(time.time()):
        return bad("二维码已过期，请让群成员重新生成", 410)
    gr = _group_row(row["gid"])
    if not gr:
        return bad("群不存在", 404)
    if int(row["uses"] or 0) >= 20:      # 一张码最多 20 人用，防止被无限转发
        return bad("这个二维码用得太多，请让群成员重新生成", 410)
    members = json.loads(gr["members"])
    if g.wxid not in members:
        members = sorted(set(members + [g.wxid]))
        db().execute("UPDATE groups SET members=? WHERE gid=?", (json.dumps(members), gr["gid"]))
    if not wrapped:
        return bad("缺少群密钥包裹")
    if len(wrapped) > 4096:
        return bad("群密钥格式不正确")
    db().execute("UPDATE group_invites SET uses=uses+1 WHERE token=?", (token,))
    db().execute("INSERT OR REPLACE INTO group_keys(gid,wxid,wrapped,wrapped_by,ver)"
                 " VALUES(?,?,?,?,?)", (gr["gid"], g.wxid, wrapped, "qr", _pubkey_ver(g.wxid)))
    db().commit()
    audit("group_join_qr", "ok", g.wxid, {"gid": gr["gid"]})
    return jsonify({"ok": True, "gid": gr["gid"], "name": gr["name"]})


@app.post("/api/groups/<gid>/members")
@auth_required
def group_add_member(gid):
    """邀请一个人进群：调用方必须已经手上有群密钥，并把「给新成员的包裹」一起传上来。

    E2E 语义：服务端只存包裹结果，看不到群密钥本身。
    权限：群主 / 群管理员；普通成员要看 allow_member_invite（默认允许）。
    """
    b = request.get_json(force=True, silent=True) or {}
    target = clean(b.get("wxid"), 32)
    wrapped = b.get("wrapped") or ""
    if not RE_WXID.match(target or ""):
        return bad("专属号格式不正确")
    if not wrapped or len(wrapped) > 4096:
        return bad("群密钥格式不正确")
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    members = json.loads(row["members"])
    if g.wxid not in members:
        return bad("你不在这个群里", 403)
    is_admin = (g.wxid == row["owner"]) or (g.wxid in _group_admins(row))
    if not is_admin and int(row["allow_member_invite"] if row["allow_member_invite"] is not None else 1) == 0:
        return bad("只有群主或管理员可以邀请新成员", 403)
    if target not in members:
        if not db().execute("SELECT 1 FROM users WHERE wxid=?", (target,)).fetchone():
            return bad("找不到这个人", 404)
        if not _mutual_friends(target):
            return bad("只能邀请好友进群", 403)
        members = sorted(set(members + [target]))
        db().execute("UPDATE groups SET members=? WHERE gid=?", (json.dumps(members), gid))
    db().execute("INSERT OR REPLACE INTO group_keys(gid,wxid,wrapped,wrapped_by,ver)"
                 " VALUES(?,?,?,?,?)", (gid, target, wrapped, g.wxid, _pubkey_ver(target)))
    db().commit()
    audit("group_add_member", "ok", g.wxid, {"gid": gid, "target": target})
    return jsonify({"ok": True, "members": members})


@app.post("/api/groups/<gid>/notice")
@auth_required
def group_notice(gid):
    """群公告：群主/管理员可改，成员都能看（随 /api/groups 下发给客户端）"""
    b = request.get_json(force=True, silent=True) or {}
    notice = clean(b.get("notice"), 1000) or ""
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if g.wxid not in json.loads(row["members"]):
        return bad("你不在这个群里", 403)
    if g.wxid != row["owner"] and g.wxid not in _group_admins(row):
        return bad("只有群主或管理员可以修改群公告", 403)
    db().execute("UPDATE groups SET notice=? WHERE gid=?", (notice, gid))
    db().commit()
    audit("group_notice", "ok", g.wxid, {"gid": gid, "len": len(notice)})
    return jsonify({"ok": True, "notice": notice})


@app.post("/api/groups/<gid>/settings")
@auth_required
def group_settings(gid):
    """群设置（目前只有「仅群主/管理员可修改群聊名称」）：群主和群管理员都能改"""
    b = request.get_json(force=True, silent=True) or {}
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if g.wxid != row["owner"] and g.wxid not in _group_admins(row):
        return bad("只有群主或管理员可以修改群设置", 403)
    v = 1 if b.get("name_admin_only") else 0
    allow_inv = 1 if b.get("allow_member_invite") else 0
    db().execute("UPDATE groups SET name_admin_only=?, allow_member_invite=? WHERE gid=?",
                 (v, allow_inv, gid))
    db().commit()
    audit("group_settings", "ok", g.wxid,
          {"gid": gid, "name_admin_only": v, "allow_member_invite": allow_inv})
    return jsonify({"ok": True, "name_admin_only": v, "allow_member_invite": allow_inv})


@app.delete("/api/groups/<gid>")
@auth_required
def group_dissolve(gid):
    """解散群聊：只有群主能操作，群和所有成员的群密钥一起删"""
    row = _group_row(gid)
    if not row:
        return bad("群不存在", 404)
    if row["owner"] != g.wxid:
        return bad("只有群主可以解散群聊", 403)
    db().execute("DELETE FROM groups WHERE gid=?", (gid,))
    db().execute("DELETE FROM group_keys WHERE gid=?", (gid,))
    db().commit()
    audit("group_dissolve", "ok", g.wxid, {"gid": gid})
    return jsonify({"ok": True})



# ---------------- 朋友圈 ----------------
# 设计：正文和图片都是客户端用「每条朋友圈一把随机 AES 密钥」加密后的密文；
# 这把密钥再按每个好友的公钥逐个包裹（Crypto.wrapKeyForMember，和群密钥同一套 ECDH），
# 服务端只存密文和包裹结果 —— 服务端看不到内容，好友能看，非好友没有包裹密钥也看不到。

def _moment_by_mid(mid):
    return db().execute("SELECT mid,author,n_images,deleted FROM moments WHERE mid=?",
                        (mid,)).fetchone()


def _moment_can_see(mid, wxid):
    """作者本人，或手上有一份包裹密钥的好友"""
    r = _moment_by_mid(mid)
    if not r or r["deleted"]:
        return None
    if r["author"] == wxid:
        return r
    k = db().execute("SELECT 1 FROM moment_keys WHERE mid=? AND wxid=?", (mid, wxid)).fetchone()
    return r if k else None


@app.post("/api/moments")
@auth_required
def create_moment():
    b = request.get_json(force=True, silent=True) or {}
    text = b.get("text") or ""
    images = b.get("images") or []
    keys = b.get("keys") or {}
    if not isinstance(text, str) or len(text) > 20000:
        return bad("正文格式不正确")
    if not isinstance(images, list) or len(images) > MAX_MOMENT_IMGS:
        return bad("最多 9 张图")
    if not isinstance(keys, dict):
        return bad("密钥格式不正确")
    if not text and not images:
        return bad("写点什么或者选张图吧")
    blobs = []
    for i, im in enumerate(images):
        if not isinstance(im, str) or len(im) > MAX_MOMENT_IMG * 4 // 3 + 32:
            return bad("第 %d 张图太大" % (i + 1), 413)
        try:
            blobs.append(base64.b64decode(im, validate=False))
        except Exception:
            return bad("第 %d 张图编码不对" % (i + 1))
    mid = "m" + secrets.token_hex(8)
    created = int(time.time() * 1000)
    os.makedirs(MOMENT_DIR, exist_ok=True)
    for i, blob in enumerate(blobs):
        with open(os.path.join(MOMENT_DIR, "%s_%d.bin" % (mid, i)), "wb") as f:
            f.write(blob)
    db().execute("INSERT INTO moments(mid,author,text_enc,n_images,created) VALUES(?,?,?,?,?)",
                 (mid, g.wxid, text, len(blobs), created))
    n_key = 0
    for wx, wrapped in keys.items():
        if not isinstance(wx, str) or not RE_WXID.match(wx) or not isinstance(wrapped, str):
            continue
        if not wrapped or len(wrapped) > 4096:
            continue
        db().execute("INSERT OR REPLACE INTO moment_keys(mid,wxid,wrapped) VALUES(?,?,?)",
                     (mid, wx, wrapped))
        n_key += 1
    db().commit()
    audit("moment_post", "ok", g.wxid, {"mid": mid, "img": len(blobs), "key": n_key})
    return jsonify({"ok": True, "mid": mid, "created": created})


@app.get("/api/moments")
@auth_required
def list_moments():
    before = request.args.get("before", type=int) or (int(time.time() * 1000) + 1)
    limit = min(max(request.args.get("limit", type=int) or 20, 1), 50)
    frows = db().execute("SELECT friend FROM friends WHERE owner=? AND state=1",
                         (g.wxid,)).fetchall()
    authors = [r["friend"] for r in frows] + [g.wxid]
    q = ("SELECT * FROM moments WHERE deleted=0 AND author IN (%s) AND created < ?"
         " ORDER BY created DESC LIMIT ?" % ",".join("?" * len(authors)))
    rows = db().execute(q, tuple(authors) + (before, limit + 1)).fetchall()
    has_more = len(rows) > limit
    out = []
    for r in rows[:limit]:
        mid = r["mid"]
        k = db().execute("SELECT wrapped FROM moment_keys WHERE mid=? AND wxid=?",
                         (mid, g.wxid)).fetchone()
        au = db().execute("SELECT nickname FROM users WHERE wxid=?", (r["author"],)).fetchone()
        likes = db().execute(
            "SELECT l.wxid,u.nickname FROM moment_likes l LEFT JOIN users u ON u.wxid=l.wxid "
            "WHERE l.mid=? ORDER BY l.created", (mid,)).fetchall()
        cms = db().execute(
            "SELECT c.cid,c.wxid,u.nickname,c.text_enc,c.created FROM moment_comments c "
            "LEFT JOIN users u ON u.wxid=c.wxid WHERE c.mid=? ORDER BY c.created", (mid,)).fetchall()
        out.append({
            "mid": mid, "author": r["author"],
            "author_nick": (au["nickname"] if au and au["nickname"] else r["author"]),
            "text_enc": r["text_enc"], "n_images": r["n_images"], "created": r["created"],
            "wrapped": (k["wrapped"] if k else ""),
            "likes": [{"wxid": x["wxid"], "nickname": x["nickname"] or x["wxid"]} for x in likes],
            "comments": [{"cid": x["cid"], "wxid": x["wxid"],
                          "nickname": x["nickname"] or x["wxid"],
                          "text_enc": x["text_enc"], "created": x["created"]} for x in cms],
        })
    return jsonify({"moments": out, "has_more": has_more})


@app.get("/api/moments/unread")
@auth_required
def moment_unread():
    since = request.args.get("since", type=int) or 0
    frows = db().execute("SELECT friend FROM friends WHERE owner=? AND state=1",
                         (g.wxid,)).fetchall()
    authors = [r["friend"] for r in frows]
    if not authors:
        return jsonify({"n": 0})
    n = db().execute(
        "SELECT count(*) c FROM moments WHERE deleted=0 AND created>? AND author IN (%s)"
        % ",".join("?" * len(authors)), (since,) + tuple(authors)).fetchone()["c"]
    return jsonify({"n": n})


@app.get("/api/moments/<mid>/img/<int:i>")
@auth_required
def moment_img(mid, i):
    r = _moment_can_see(mid, g.wxid)
    if not r:
        return bad("朋友圈不存在或没有权限", 404)
    if i < 0 or i >= r["n_images"]:
        return bad("图片不存在", 404)
    p = os.path.join(MOMENT_DIR, "%s_%d.bin" % (mid, i))
    if not os.path.exists(p):
        return bad("图片不存在", 404)
    return send_file(p, mimetype="application/octet-stream")


@app.post("/api/moments/<mid>/like")
@auth_required
def moment_like(mid):
    if not _moment_can_see(mid, g.wxid):
        return bad("朋友圈不存在或没有权限", 404)
    row = db().execute("SELECT 1 FROM moment_likes WHERE mid=? AND wxid=?",
                       (mid, g.wxid)).fetchone()
    if row:
        db().execute("DELETE FROM moment_likes WHERE mid=? AND wxid=?", (mid, g.wxid))
        liked = False
    else:
        db().execute("INSERT INTO moment_likes(mid,wxid,created) VALUES(?,?,?)",
                     (mid, g.wxid, int(time.time())))
        liked = True
    db().commit()
    return jsonify({"ok": True, "liked": liked})


@app.post("/api/moments/<mid>/comment")
@auth_required
def moment_comment(mid):
    b = request.get_json(force=True, silent=True) or {}
    text = b.get("text") or ""
    if not isinstance(text, str) or not text or len(text) > 8000:
        return bad("评论内容不对")
    if not _moment_can_see(mid, g.wxid):
        return bad("朋友圈不存在或没有权限", 404)
    cid = "c" + secrets.token_hex(8)
    db().execute("INSERT INTO moment_comments(cid,mid,wxid,text_enc,created) VALUES(?,?,?,?,?)",
                 (cid, mid, g.wxid, text, int(time.time() * 1000)))
    db().commit()
    return jsonify({"ok": True, "cid": cid})


@app.delete("/api/moments/<mid>")
@auth_required
def moment_delete(mid):
    r = _moment_by_mid(mid)
    if not r or r["deleted"]:
        return bad("朋友圈不存在", 404)
    if r["author"] != g.wxid:
        return bad("只能删自己的", 403)
    db().execute("UPDATE moments SET deleted=1 WHERE mid=?", (mid,))
    db().execute("DELETE FROM moment_likes WHERE mid=?", (mid,))
    db().execute("DELETE FROM moment_comments WHERE mid=?", (mid,))
    db().execute("DELETE FROM moment_keys WHERE mid=?", (mid,))
    db().commit()
    for i in range(r["n_images"]):
        p = os.path.join(MOMENT_DIR, "%s_%d.bin" % (mid, i))
        if os.path.exists(p):
            os.remove(p)
    audit("moment_delete", "ok", g.wxid, {"mid": mid})
    return jsonify({"ok": True})


if __name__ == "__main__":
    init_db()
    _port = int(os.environ.get("PORT", "8097"))
    _cert = os.environ.get("CHAT_TLS_CERT", "")
    _key = os.environ.get("CHAT_TLS_KEY", "")
    if _cert and _key:
        print("[tls] 已启用 HTTPS：%s" % _cert, flush=True)
        app.run(host="0.0.0.0", port=_port, ssl_context=(_cert, _key))
    else:
        print("[warn] 当前是明文 HTTP —— 只应在局域网使用；要对外请设 "
              "CHAT_TLS_CERT / CHAT_TLS_KEY（或用 nginx 终止 TLS）", flush=True)
        app.run(host="0.0.0.0", port=_port)
else:
    init_db()
