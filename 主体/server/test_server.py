#!/usr/bin/env python3
"""灵珑聊天服务端 —— 自测（不依赖外网，Flask test client + 临时数据库）"""
import json
import os
import shutil
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="chat_test_")
os.environ["CHAT_DATA_DIR"] = TMP

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as srv  # noqa: E402

srv.init_db()
c = srv.app.test_client()


def ok(cond, msg):
    print(("✅ " if cond else "❌ ") + msg)
    if not cond:
        raise SystemExit("测试失败: " + msg)


def post(path, body, token=None):
    h = {"Authorization": "Bearer " + token} if token else {}
    return c.post(path, data=json.dumps(body), content_type="application/json", headers=h)


def get(path, token=None):
    h = {"Authorization": "Bearer " + token} if token else {}
    return c.get(path, headers=h)


try:
    # 1. 健康检查
    r = get("/api/health")
    ok(r.status_code == 200 and r.get_json()["ok"], "健康检查")

    # 2. 注册两个人
    r = post("/api/register", {
        "wxid": "papa2026", "password": "pass123456", "nickname": "爸爸",
        "pubkey": "PK_PAPA", "privkey_enc": "ENC_PAPA", "kdf_salt": "SALT_PAPA",
        "email": "papa2026@t.local"})
    ok(r.status_code == 200, "注册 papa2026")

    r = post("/api/register", {
        "wxid": "kid2026", "password": "pass123456", "nickname": "孩子",
        "pubkey": "PK_KID", "privkey_enc": "ENC_KID", "kdf_salt": "SALT_KID",
        "email": "kid2026@t.local"})
    ok(r.status_code == 200, "注册 kid2026")

    # 3. 重复注册被拒
    r = post("/api/register", {
        "wxid": "papa2026", "password": "pass123456", "nickname": "x",
        "pubkey": "x", "privkey_enc": "x", "kdf_salt": "x",
        "email": "papa2026@t.local"})
    ok(r.status_code == 409, "重复专属号被拒（409）")

    # 4. 非法专属号
    r = post("/api/register", {
        "wxid": "坏 名字", "password": "pass123456", "nickname": "x",
        "pubkey": "x", "privkey_enc": "x", "kdf_salt": "x",
        "email": "badname@t.local"})
    ok(r.status_code == 400, "非法专属号被拒（400）")

    # 5. 密码错
    r = post("/api/login", {"wxid": "papa2026", "password": "wrong"})
    ok(r.status_code == 401, "错误密码被拒（401）")

    # 6. 登录拿 token
    r = post("/api/login", {"wxid": "papa2026", "password": "pass123456"})
    j = r.get_json()
    ok(r.status_code == 200 and j["token"] and j["pubkey"] == "PK_PAPA"
       and j["privkey_enc"] == "ENC_PAPA", "登录成功并返回公钥+加密私钥")
    t_papa = j["token"]

    r = post("/api/login", {"wxid": "kid2026", "password": "pass123456"})
    t_kid = r.get_json()["token"]
    ok(bool(t_kid), "kid 登录")

    # 7. 未授权访问
    r = get("/api/me")
    ok(r.status_code == 401, "无 token 访问被拒（401）")

    # 8. 搜人
    r = get("/api/search?q=kid", t_papa)
    users = r.get_json()["users"]
    ok(len(users) == 1 and users[0]["wxid"] == "kid2026" and users[0]["pubkey"] == "PK_KID",
       "按专属号搜索到对方并拿到公钥")

    # 9. 单聊发消息（内容是密文；服务端不解析）
    r = post("/api/messages", {"to_type": "user", "to_id": "kid2026",
                               "payload": "CIPHER_HELLO"}, t_papa)
    ok(r.status_code == 200, "单聊发消息")

    # 10. 对方能拉到
    r = get("/api/messages?since=0", t_kid)
    msgs = r.get_json()["messages"]
    ok(len(msgs) == 1 and msgs[0]["payload"] == "CIPHER_HELLO" and msgs[0]["from"] == "papa2026",
       "收件人拉到密文")
    cursor = r.get_json()["cursor"]

    # 11. 发件人自己也能拉到（多设备同步需要）；但不能拉到别人的单聊
    r = get("/api/messages?since=0", t_papa)
    ms = r.get_json()["messages"]
    ok(len(ms) == 1 and ms[0]["from"] == "papa2026" and ms[0]["to_id"] == "kid2026",
       "发件人能拉到自己的单聊（供多设备同步），且不混入别人的")

    # 12. 增量游标
    r = get("/api/messages?since=%d" % cursor, t_kid)
    ok(len(r.get_json()["messages"]) == 0, "增量拉取不重复")

    # 13. 建群（3 人：papa / kid / 手动加个 mama）
    post("/api/register", {
        "wxid": "mama2026", "password": "pass123456", "nickname": "妈妈",
        "pubkey": "PK_MAMA", "privkey_enc": "ENC_MAMA", "kdf_salt": "SALT_MAMA",
        "email": "mama2026@t.local"})
    t_mama = post("/api/login", {"wxid": "mama2026", "password": "pass123456"}).get_json()["token"]
    # v0.1.4：只能把好友拉进群
    for _w, _tok in (("kid2026", t_kid), ("mama2026", t_mama)):
        post("/api/friends", {"wxid": _w}, t_papa)
        post("/api/friends/accept", {"wxid": "papa2026"}, _tok)
    r = post("/api/groups", {"name": "一家人", "members": {
        "papa2026": "WRAP_PAPA", "kid2026": "WRAP_KID", "mama2026": "WRAP_MAMA"}}, t_papa)
    ok(r.status_code == 200 and r.get_json()["gid"], "建群成功")
    gid = r.get_json()["gid"]

    # 14. 成员能看到群和各自那份加密群密钥
    r = get("/api/groups", t_kid)
    groups = r.get_json()["groups"]
    ok(len(groups) == 1 and groups[0]["gid"] == gid and groups[0]["my_wrapped"] == "WRAP_KID"
       and "mama2026" in groups[0]["members"], "成员看到群 + 自己的群密钥份额")

    # 15. 群发消息 → 所有成员都能拉到
    r = post("/api/messages", {"to_type": "group", "to_id": gid, "payload": "CIPHER_GROUP"}, t_kid)
    ok(r.status_code == 200, "群发消息")
    for name, tok in (("papa", t_papa), ("kid", t_kid)):
        r = get("/api/messages?since=0", tok)
        found = any(m["payload"] == "CIPHER_GROUP" for m in r.get_json()["messages"])
        ok(found, "群成员 %s 收到群消息" % name)

    # 16. 非成员不能往群里发
    r = post("/api/register", {
        "wxid": "stranger", "password": "pass123456", "nickname": "外人",
        "pubkey": "PK_S", "privkey_enc": "ENC_S", "kdf_salt": "SALT_S",
        "email": "stranger@t.local"})
    t_s = post("/api/login", {"wxid": "stranger", "password": "pass123456"}).get_json()["token"]
    r = post("/api/messages", {"to_type": "group", "to_id": gid, "payload": "X"}, t_s)
    ok(r.status_code == 403, "非成员发群消息被拒（403）")

    # 17. 登出后 token 失效
    post("/api/logout", {}, t_s)
    r = get("/api/me", t_s)
    ok(r.status_code == 401, "登出后 token 失效")

    # 18. 服务端库里没有明文密码
    import sqlite3
    con = sqlite3.connect(os.path.join(TMP, "chat.db"))
    row = con.execute("SELECT pw_hash, pw_salt FROM users WHERE wxid='papa2026'").fetchone()
    con.close()
    ok(row[0] != "pass123456" and len(row[0]) == 64, "密码只存 PBKDF2 哈希")

    print("\n全部通过 ✅")
finally:
    shutil.rmtree(TMP, ignore_errors=True)
