#!/usr/bin/env python3
"""换钥自动同步自测（v0.0.6）——「找回账号后还能不能聊天」的服务端半边

覆盖：
  ① 批量取公钥 /api/pubkeys（含版本号），只回给登录用户
  ② 找回时换新密钥对 → pubkey_ver 自增、pubkey 变化 → 好友下一次拉取就能拿到新的
  ③ 群密钥重包裹 /api/groups/<gid>/rewrap（任何群成员都能帮换钥的人补一份）
  ④ 非群成员不能 rewrap；不在群里的人不能被 rewrap
"""
import json
import os
import shutil
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="chat_keysync_")
os.environ["CHAT_DATA_DIR"] = TMP
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as srv  # noqa: E402

srv.init_db()
c = srv.app.test_client()
FAILS = []


def ok(cond, msg):
    print(("✅ " if cond else "❌ ") + msg)
    if not cond:
        FAILS.append(msg)


def post(p, b, t=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    return c.post(p, data=json.dumps(b), content_type="application/json", headers=h)


def get(p, t=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    return c.get(p, headers=h)


try:
    # ---- 准备：三个人 ----
    for w, pk in (("alice", "PK_A1"), ("bob", "PK_B1"), ("eve", "PK_E1")):
        r = post("/api/register", {"wxid": w, "password": "pass123456", "nickname": w,
                                   "pubkey": pk, "privkey_enc": "E_" + w, "kdf_salt": "S_" + w,
                                   "email": w + "@t.local"})
        ok(r.status_code == 200, "注册 " + w)
    ta = post("/api/login", {"wxid": "alice", "password": "pass123456"}).get_json()["token"]
    tb = post("/api/login", {"wxid": "bob", "password": "pass123456"}).get_json()["token"]
    te = post("/api/login", {"wxid": "eve", "password": "pass123456"}).get_json()["token"]

    # ---- ① 未登录不给公钥 ----
    r = get("/api/pubkeys?ids=alice,bob")
    ok(r.status_code == 401, "未登录取公钥被拒（401）")

    r = get("/api/pubkeys?ids=alice,bob", tb)
    j = r.get_json()
    ok(r.status_code == 200, "登录后批量取公钥 200")
    ok(j["users"]["alice"]["pubkey"] == "PK_A1" and j["users"]["alice"]["ver"] == 1,
       "初始公钥 + 版本号 1")
    ok("bob" in j["users"], "批量返回包含多个好友")

    r = get("/api/pubkeys?ids=%20,bad%20id!,", tb)
    ok(r.status_code == 200 and r.get_json()["users"] == {}, "非法/空 id 被过滤，不报错")

    r = get("/api/pubkeys?ids=" + ",".join(["alice"] * 80), tb)
    ok(r.status_code == 200, "重复 id 去重后不熔断")

    # ---- ② 找回时换新密钥对 ----
    r = post("/api/recovery/apply", {"wxid": "alice", "contact": "alice@t.local"})
    ok(r.status_code == 200, "alice 申请找回")
    key128 = r.get_json()["key128"]
    r = post("/api/recovery/approve", {"wxid": "alice", "key128": key128}, tb)
    ok(r.status_code == 200, "bob 批准找回")
    r = post("/api/recovery/finish", {"wxid": "alice", "key128": key128,
                                      "password": "newpass123", "privkey_enc": "E_A2",
                                      "kdf_salt": "S_A2", "pubkey": "PK_A2"})
    ok(r.status_code == 200 and r.get_json()["new_keypair"] is True, "alice 换新密钥对完成")

    r = get("/api/pubkeys?ids=alice", tb)
    u = r.get_json()["users"]["alice"]
    ok(u["pubkey"] == "PK_A2", "好友下一次拉取即可拿到新公钥（不需要重新加好友）")
    ok(u["ver"] == 2, "公钥版本号自增到 2")

    # 登录接口也带版本号
    ta2 = post("/api/login", {"wxid": "alice", "password": "newpass123"}).get_json()
    ok(ta2.get("pubkey_ver") == 2 and ta2.get("pubkey") == "PK_A2",
       "登录返回新公钥与版本号")

    # 不换密钥的找回（本机保留旧私钥）不应改变版本号
    r = post("/api/recovery/apply", {"wxid": "bob", "contact": "bob@t.local"})
    k2 = r.get_json()["key128"]
    post("/api/recovery/approve", {"wxid": "bob", "key128": k2}, ta2["token"])
    r = post("/api/recovery/finish", {"wxid": "bob", "key128": k2, "password": "newpass456",
                                      "privkey_enc": "E_B2", "kdf_salt": "S_B2"})
    ok(r.status_code == 200 and r.get_json()["new_keypair"] is False, "bob 只改密码（不换钥）")
    tb2 = post("/api/login", {"wxid": "bob", "password": "newpass456"}).get_json()
    ok(tb2.get("pubkey_ver") == 1 and tb2.get("pubkey") == "PK_B1", "不换钥则版本号不变")

    # ---- ③ 群密钥重包裹 ----
    ta2 = ta2["token"]
    tb2 = tb2["token"]
    # v0.1.4：只能把好友拉进群
    post("/api/friends", {"wxid": "bob"}, ta2)
    post("/api/friends/accept", {"wxid": "alice"}, tb2)
    r = post("/api/groups", {"name": "家", "members": {"alice": "W_A", "bob": "W_B"}}, ta2)
    gid = r.get_json()["gid"]
    ok(r.status_code == 200, "建群（alice 群主）")

    r = get("/api/groups", tb2)
    g = r.get_json()["groups"][0]
    ok(g["my_wrapped"] == "W_B" and g["my_wrapped_by"] == "alice", "群成员看到包裹者是谁")

    # bob（有群密钥）替换了钥的 alice 重新包裹
    # v0.1.4：公钥版本没前进时不允许覆盖别人的包裹
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice", "wrapped": "W_A2"}, tb2)
    ok(r.status_code == 409, "公钥没变时 rewrap 被拒（防覆盖搞坏）")
    import sqlite3 as _sq
    _con = _sq.connect(os.path.join(TMP, "chat.db"))
    _con.execute("UPDATE users SET pubkey_ver=pubkey_ver+1 WHERE wxid='alice'")
    _con.commit(); _con.close()
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice", "wrapped": "W_A2"}, tb2)
    ok(r.status_code == 200, "群成员替换钥的人重新包裹群密钥（换钥后）")
    r = get("/api/groups", ta2)
    g = r.get_json()["groups"][0]
    ok(g["my_wrapped"] == "W_A2" and g["my_wrapped_by"] == "bob",
       "换钥的人拿到新包裹 + 包裹者是 bob")

    # ---- ④ 越权 ----
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice", "wrapped": "X"}, te)
    ok(r.status_code == 403, "非群成员不能 rewrap（403）")
    r = post("/api/groups/%s/rewrap" % gid, {"member": "eve", "wrapped": "X"}, tb2)
    ok(r.status_code == 404, "不能给群外的人包裹（404）")
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice"}, tb2)
    ok(r.status_code == 400, "缺 wrapped 参数被拒（400）")
    r = post("/api/groups/nosuchgid/rewrap", {"member": "alice", "wrapped": "X"}, tb2)
    ok(r.status_code == 404, "群不存在（404）")

    # 旧库迁移：group_keys.wrapped_by 必须存在且有值
    import sqlite3
    con = sqlite3.connect(os.path.join(TMP, "chat.db"))
    cols = [x[1] for x in con.execute("PRAGMA table_info(group_keys)")]
    con.close()
    ok("wrapped_by" in cols, "group_keys 有 wrapped_by 列（老库可迁移）")

    print("\n%d 项通过 ✅" % (30 - len(FAILS)) if not FAILS else "\n失败：%s" % FAILS)
finally:
    shutil.rmtree(TMP, ignore_errors=True)

if FAILS:
    raise SystemExit(1)
