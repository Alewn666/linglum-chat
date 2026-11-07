#!/usr/bin/env python3
"""V0.0.8：通讯录（好友表）+ 个人资料（昵称/头像）自测"""
import base64, json, os, shutil, sys, tempfile
TMP = tempfile.mkdtemp(prefix="chat_pf_"); os.environ["CHAT_DATA_DIR"] = TMP
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as srv
srv.init_db(); c = srv.app.test_client(); FAILS = []
def ok(c_, m):
    print(("✅ " if c_ else "❌ ") + m)
    if not c_: FAILS.append(m)
def post(p, b, t=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    return c.post(p, data=json.dumps(b), content_type="application/json", headers=h)
def get(p, t=None):
    return c.get(p, headers={"Authorization": "Bearer " + t} if t else {})
try:
    for w in ("zhangsan", "lisi"):
        ok(post("/api/register", {"wxid": w, "password": "pass123456", "nickname": w,
                                  "pubkey": "PK_" + w, "privkey_enc": "E", "kdf_salt": "S",
                                  "email": w + "@t.local"}).status_code == 200, "注册 " + w)
    tz = post("/api/login", {"wxid": "zhangsan", "password": "pass123456"}).get_json()
    tl = post("/api/login", {"wxid": "lisi", "password": "pass123456"}).get_json()["token"]
    ok(tz.get("avatar_ver") == 0, "新账号 avatar_ver=0")
    # 加好友 = 发申请，对方通过才算（v0.1.4 安全改版）
    r = post("/api/friends", {"wxid": "lisi"}, tz["token"]).get_json()
    ok(r.get("state") == "pending", "张 加 李 → 只生成申请")
    ok(get("/api/friends", tz["token"]).get_json()["friends"] == [], "没通过前张的通讯录里没有李")
    ok(get("/api/friends", tl).get_json()["friends"] == [], "李 的通讯录没被偷偷写进张")
    req = get("/api/friends/requests", tl).get_json()
    ok(len(req["incoming"]) == 1 and req["incoming"][0]["wxid"] == "zhangsan", "李 能看到待处理申请")
    ok(post("/api/friends/accept", {"wxid": "zhangsan"}, tl).status_code == 200, "李 通过申请")
    r = get("/api/friends", tz["token"]).get_json()["friends"]
    ok(len(r) == 1 and r[0]["wxid"] == "lisi" and r[0]["nickname"] == "lisi", "张 的通讯录有李")
    r = get("/api/friends", tl).get_json()["friends"]
    ok(len(r) == 1 and r[0]["wxid"] == "zhangsan", "李 的通讯录也有张")
    ok(post("/api/friends", {"wxid": "lisi"}, tz["token"]).get_json().get("state") == "friends",
       "已是好友时再加一次幂等")
    ok(len(get("/api/friends", tl).get_json()["friends"]) == 1, "幂等不重复")
    ok(post("/api/friends", {"wxid": "zhangsan"}, tz["token"]).status_code == 400, "不能加自己")
    ok(post("/api/friends", {"wxid": "nobody"}, tz["token"]).status_code == 404, "加不存在的人 404")
    # 昵称
    r = post("/api/profile", {"nickname": "小张"}, tz["token"]).get_json()
    ok(r["nickname"] == "小张", "改昵称")
    ok(get("/api/friends", tl).get_json()["friends"][0]["nickname"] == "小张", "好友侧看到新昵称")
    # 头像
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 100).decode()
    r = post("/api/profile", {"avatar": png}, tz["token"]).get_json()
    ok(r["avatar_ver"] == 1, "上传头像 avatar_ver 1")
    rr = c.get("/api/avatar/zhangsan", headers={"Authorization": "Bearer " + tl})
    ok(rr.status_code == 200 and rr.data[:4] == b"\x89PNG", "好友能取到我的头像（PNG 魔数对）")
    ok(c.get("/api/avatar/zhangsan").status_code == 401, "未登录取头像 401")
    ok(c.get("/api/avatar/nobody2", headers={"Authorization": "Bearer " + tl}).status_code == 404, "没头像 404")
    ok(post("/api/profile", {"avatar": base64.b64encode(b"GIF89a").decode()}, tz["token"]).status_code == 400, "非 PNG/JPEG 被拒")
    ok(post("/api/profile", {"avatar": base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * (500 * 1024)).decode()}, tz["token"]).status_code == 413, "超大头像 413")
    ok(get("/api/friends", tl).get_json()["friends"][0]["avatar_ver"] == 1, "好友侧看到 avatar_ver")
    # 删好友
    ok(c.delete("/api/friends/lisi", headers={"Authorization": "Bearer " + tz["token"]}).status_code == 200, "删好友")
    ok(get("/api/friends", tz["token"]).get_json()["friends"] == [], "张 的通讯录清空")
    ok(len(get("/api/friends", tl).get_json()["friends"]) == 1, "李 的通讯录仍在（单向删，不再替对方删）")
    print("\n全部通过 ✅" if not FAILS else "\n失败：%s" % FAILS)
finally:
    shutil.rmtree(TMP, ignore_errors=True)
if FAILS: raise SystemExit(1)
