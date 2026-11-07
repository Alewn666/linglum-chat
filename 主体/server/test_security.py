#!/usr/bin/env python3
"""V0.1.4 安全回归（越权 / 好友批准 / 群权限 / 令牌 / 锁定 / 扫码限次）

覆盖本轮加固点：
  ① LIKE 通配符越权：wxid 带下划线的人也拿不到别人的群与群消息
  ② 好友需对方通过；待通过的人看不到对方朋友圈
  ③ 群：非好友不能拉进群；rewrap 不能覆盖没换钥的人
  ④ 令牌只存哈希 + 旧明文令牌自动升级
  ⑤ 登录按账号锁定（换 IP 也绕不过）
  ⑥ 扫码入群 token 限 20 次
  ⑦ 搜索里 % _ 当字面量（不能枚举全站用户）
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="chat_sec_")
os.environ["CHAT_DATA_DIR"] = TMP
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as srv  # noqa: E402

srv.init_db()
c = srv.app.test_client()
FAILS = []
DB = os.path.join(TMP, "chat.db")


def ok(cond, msg):
    print(("✅ " if cond else "❌ ") + msg)
    if not cond:
        FAILS.append(msg)


def post(p, b, t=None, hdr=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    if hdr:
        h.update(hdr)
    return c.post(p, data=json.dumps(b), content_type="application/json", headers=h)


def get(p, t=None, hdr=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    if hdr:
        h.update(hdr)
    return c.get(p, headers=h)


def reg(wx):
    r = post("/api/register", {"wxid": wx, "password": "pass123456", "nickname": wx,
                               "pubkey": "PK_" + wx, "privkey_enc": "E", "kdf_salt": "S",
                               "email": wx + "@t.local"})
    ok(r.status_code == 200, "注册 " + wx)
    return post("/api/login", {"wxid": wx, "password": "pass123456"}).get_json()["token"]


def q(sql, args=()):
    con = sqlite3.connect(DB)
    try:
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


def w(sql, args=()):
    con = sqlite3.connect(DB)
    try:
        con.execute(sql, args)
        con.commit()
    finally:
        con.close()


try:
    # a_b 带下划线：旧版 LIKE '%"a_b"%' 会把 acb 也匹配上
    t_ab = reg("a_b")
    t_acb = reg("acb")
    t_x = reg("xuser")
    t_y = reg("yuser")

    # ---------- ① LIKE 越权 ----------
    gid = post("/api/groups", {"name": "acb的群", "members": {"acb": "W"}}, t_acb).get_json().get("gid")
    ok(bool(gid), "acb 建群成功")
    post("/api/messages", {"to_type": "group", "to_id": gid, "payload": "SECRET_CIPHER"}, t_acb)
    mine = [g["gid"] for g in get("/api/groups", t_ab).get_json()["groups"]]
    ok(gid not in mine, "a_b 看不到 acb 的群（下划线不再当通配符）")
    msgs = get("/api/messages?since=0", t_ab).get_json()["messages"]
    ok(all(m["to_id"] != gid for m in msgs), "a_b 拉不到 acb 群里的消息")
    ok(len(get("/api/groups", t_acb).get_json()["groups"]) == 1, "acb 自己看得到自己的群")

    # ---------- ② 好友需通过 + 待通过看不到朋友圈 ----------
    r = post("/api/friends", {"wxid": "xuser"}, t_y).get_json()
    ok(r.get("state") == "pending", "yuser 加 xuser 只生成申请")
    ok(get("/api/friends", t_y).get_json()["friends"] == [], "没通过前 yuser 通讯录里没有 xuser")
    ok(get("/api/friends", t_x).get_json()["friends"] == [], "xuser 也没被单向写进 yuser")
    post("/api/moments", {"text": "ENC", "keys": {"yuser": "W_SELF"}}, t_y)
    ok(len(get("/api/moments", t_x).get_json()["moments"]) == 0, "待通过的人看不到对方朋友圈")
    ok(post("/api/friends/reject", {"wxid": "yuser"}, t_x).status_code == 200, "xuser 拒绝申请")
    ok(get("/api/friends/requests", t_x).get_json()["incoming"] == [], "拒绝后申请消失")

    # ---------- ③ 群权限 ----------
    r = post("/api/groups/%s/members" % gid, {"wxid": "xuser", "wrapped": "W_X"}, t_acb)
    ok(r.status_code == 403, "非好友不能被拉进群（403）")
    r = post("/api/groups/%s/rewrap" % gid, {"member": "acb", "wrapped": "BAD"}, t_acb)
    ok(r.status_code in (200, 409), "自己 rewrap 不会 500")
    # 让 xuser 也进群（先加好友）→ 再验证覆盖保护
    post("/api/friends", {"wxid": "acb"}, t_x)
    post("/api/friends/accept", {"wxid": "xuser"}, t_acb)
    r = post("/api/groups/%s/members" % gid, {"wxid": "xuser", "wrapped": "W_X"}, t_acb)
    ok(r.status_code == 200, "好友可以被拉进群")
    r = post("/api/groups/%s/rewrap" % gid, {"member": "acb", "wrapped": "BAD"}, t_x)
    ok(r.status_code == 409, "公钥没变时不能覆盖别人的包裹（409）")

    # ---------- ④ 令牌只存哈希 + 旧令牌升级 ----------
    rows = [r[0] for r in q("SELECT token FROM tokens")]
    ok(rows and all(t.startswith("h:") for t in rows), "库里令牌全部是哈希")
    ok(all("h:" + t not in rows and t not in rows for t in [t_ab, t_acb]), "明文令牌不在库里")
    ok(get("/api/me", t_ab).status_code == 200, "明文令牌仍能正常鉴权（客户端不用改）")
    w("INSERT INTO tokens(token,wxid,created) VALUES(?,?,?)", ("legacy_plain_tok", "acb", 9999999999))
    ok(get("/api/me", "legacy_plain_tok").status_code == 200, "旧明文令牌可用（兼容）")
    left = q("SELECT token FROM tokens WHERE token=?", ("legacy_plain_tok",))
    upg = q("SELECT token FROM tokens WHERE wxid='acb'")
    ok(not left and any(t[0].startswith("h:") for t in upg), "旧令牌用一次后自动升级成哈希")

    # ---------- ⑤ 登录按账号锁定（换 IP 也绕不过） ----------
    for i in range(5):
        post("/api/login", {"wxid": "a_b", "password": "wrongpass"})
    r = post("/api/login", {"wxid": "a_b", "password": "pass123456"},
             hdr={"X-Forwarded-For": "203.0.113.9"})
    ok(r.status_code == 429, "连错 5 次后换 IP 也被账号锁拦住（429）")

    # ---------- ⑥ 扫码入群限次 ----------
    tok = post("/api/groups/%s/qr" % gid, {}, t_acb).get_json().get("token")
    ok(bool(tok), "生成群二维码 token")
    w("UPDATE group_invites SET uses=20 WHERE token=?", (tok,))
    r = post("/api/groups/join", {"token": tok, "wrapped": "W_X"}, t_y)
    ok(r.status_code == 410, "二维码用满 20 次后被拒（410）")

    # ---------- ⑥½ 删好友 = 彻底删除（双方一起移除，含待处理申请） ----------
    # 先用两个跟别人没关系的号，避免"互相申请会自动通过"干扰本项
    t_p = reg("puser")
    t_q = reg("quser")
    post("/api/friends", {"wxid": "quser"}, t_p)          # puser 申请 quser（挂着，未通过）
    ok(len(get("/api/friends/requests", t_q).get_json()["incoming"]) == 1, "quser 有一条待处理申请")
    post("/api/friends", {"wxid": "puser"}, t_q)          # quser 反过来申请 → 自动成为好友
    ok(len(get("/api/friends", t_p).get_json()["friends"]) == 1, "互相申请自动成为好友")
    # 再挂一条「只申请、不通过」的关系：xuser → yuser
    post("/api/friends", {"wxid": "yuser"}, t_x)
    ok(any(r0["wxid"] == "xuser" for r0 in get("/api/friends/requests", t_y).get_json()["incoming"]),
       "yuser 有一条来自 xuser 的待处理申请")
    r = c.delete("/api/friends/quser", headers={"Authorization": "Bearer " + t_p})
    ok(r.status_code == 200, "删除好友（双删）")
    ok(all(f["wxid"] != "quser" for f in get("/api/friends", t_p).get_json()["friends"]),
       "我这边通讯录里没有了")
    ok(all(f["wxid"] != "puser" for f in get("/api/friends", t_q).get_json()["friends"]),
       "对方通讯录里也没有了（彻底双删）")
    # 删 xuser→yuser 这条关系后，xuser 挂在 yuser 那里的待处理申请也应一起消失
    c.delete("/api/friends/yuser", headers={"Authorization": "Bearer " + t_x})
    ok(all(r0["wxid"] != "xuser" for r0 in get("/api/friends/requests", t_y).get_json()["incoming"]),
       "残留的待处理申请也一并清掉（不会通过一下又加回来）")

    # ---------- ⑦ 搜索通配符 ----------

    r = get("/api/search?q=%25", t_x)     # "%"
    ok(r.get_json()["users"] == [], "% 不再当通配符（搜不到全站用户）")
    r = get("/api/search?q=a_", t_ab)
    ok(all(u["wxid"].startswith("a_") for u in r.get_json()["users"]), "_ 也当字面量")

    print("\n全部通过 ✅" if not FAILS else "\n失败：%s" % FAILS)
finally:
    shutil.rmtree(TMP, ignore_errors=True)

if FAILS:
    raise SystemExit(1)
