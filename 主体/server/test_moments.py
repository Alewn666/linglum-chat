#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""朋友圈服务端自测：可见性 / 包裹密钥 / 图片权限 / 点赞切换 / 评论 / 删除 / 参数校验"""
import base64, json, os, shutil, sys, tempfile

TMP = tempfile.mkdtemp(prefix="chat_mm_")
os.environ["CHAT_DATA_DIR"] = TMP
sys.path.insert(0, "/vol1/1000/项目/安卓/chat/主体/server")
import app as srv
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
    return c.get(p, headers={"Authorization": "Bearer " + t} if t else {})

def delete(p, t=None):
    return c.delete(p, headers={"Authorization": "Bearer " + t} if t else {})

try:
    for w in ("maa", "mbb", "mcc"):
        ok(post("/api/register", {"wxid": w, "password": "pass123456", "nickname": w.upper(),
                                  "pubkey": "PK_" + w, "privkey_enc": "E", "kdf_salt": "S",
                                  "email": w + "@t.local"}).status_code == 200,
           "注册 " + w)
    ta = post("/api/login", {"wxid": "maa", "password": "pass123456"}).get_json()["token"]
    tb = post("/api/login", {"wxid": "mbb", "password": "pass123456"}).get_json()["token"]
    tc = post("/api/login", {"wxid": "mcc", "password": "pass123456"}).get_json()["token"]
    post("/api/friends", {"wxid": "mbb"}, ta)
    post("/api/friends/accept", {"wxid": "maa"}, tb)      # v0.1.4：加好友要对方通过

    ok(post("/api/moments", {}, ta).status_code == 400, "空内容被拒")
    ok(post("/api/moments", {"images": ["QUJD"] * 10}, ta).status_code == 400, "10 张图被拒")
    ok(post("/api/moments", {"text": "x" * 20001}, ta).status_code == 400, "正文超长被拒")

    img1 = base64.b64encode(b"\xde\xad\xbe\xefIMG1").decode()
    img2 = base64.b64encode(b"\xde\xad\xbe\xefIMG2").decode()
    r = post("/api/moments", {"text": "ENC_TEXT_1", "images": [img1, img2],
                              "keys": {"maa": "WRAP_A", "mbb": "WRAP_B"}}, ta).get_json()
    mid = r.get("mid")
    ok(r.get("ok") and mid, "发朋友圈成功 mid=%s" % mid)

    fa = get("/api/moments", ta).get_json()["moments"]
    fb = get("/api/moments", tb).get_json()["moments"]
    fc = get("/api/moments", tc).get_json()["moments"]
    ok(len(fa) == 1 and fa[0]["wrapped"] == "WRAP_A" and fa[0]["n_images"] == 2, "自己看得到，带自己的包裹密钥")
    ok(len(fb) == 1 and fb[0]["wrapped"] == "WRAP_B" and fb[0]["author_nick"] == "MAA", "好友看得到，带好友的包裹密钥+昵称")
    ok(len(fc) == 0, "非好友看不到")

    ok(get("/api/moments/%s/img/0" % mid, tb).status_code == 200, "好友能取图片")
    ok(get("/api/moments/%s/img/0" % mid, tb).data == b"\xde\xad\xbe\xefIMG1", "图片内容字节一致")
    ok(get("/api/moments/%s/img/9" % mid, tb).status_code == 404, "越界图片 404")
    ok(get("/api/moments/%s/img/0" % mid, tc).status_code in (403, 404), "非好友取图被拒")

    ok(post("/api/moments/%s/like" % mid, {}, tb).get_json()["liked"] is True, "好友点赞")
    like = get("/api/moments", tb).get_json()["moments"][0]["likes"]
    ok(len(like) == 1 and like[0]["wxid"] == "mbb" and like[0]["nickname"] == "MBB", "点赞列表带昵称")
    ok(post("/api/moments/%s/like" % mid, {}, tb).get_json()["liked"] is False, "再点取消")
    ok(get("/api/moments", tb).get_json()["moments"][0]["likes"] == [], "取消后列表为空")
    ok(post("/api/moments/%s/like" % mid, {}, tc).status_code in (403, 404), "非好友不能点赞")

    ok(post("/api/moments/%s/comment" % mid, {"text": "ENC_C1"}, tb).status_code == 200, "好友评论")
    ok(post("/api/moments/%s/comment" % mid, {"text": ""}, tb).status_code == 400, "空评论被拒")
    ok(post("/api/moments/%s/comment" % mid, {"text": "X"}, tc).status_code in (403, 404), "非好友不能评论")
    cm = get("/api/moments", ta).get_json()["moments"][0]["comments"]
    ok(len(cm) == 1 and cm[0]["text_enc"] == "ENC_C1" and cm[0]["nickname"] == "MBB", "作者看到评论+昵称")

    ok(get("/api/moments/unread?since=0", tb).get_json()["n"] == 1, "好友未读数 1")
    ok(get("/api/moments/unread?since=0", tc).get_json()["n"] == 0, "非好友未读数 0")

    ok(delete("/api/moments/%s" % mid, tb).status_code == 403, "好友不能删")
    ok(delete("/api/moments/%s" % mid, ta).status_code == 200, "作者删除")
    ok(get("/api/moments", tb).get_json()["moments"] == [], "删后好友看不到")
    ok(get("/api/moments/%s/img/0" % mid, tb).status_code == 404, "删后图片取不到")
    ok(os.listdir(srv.MOMENT_DIR) == [], "图片文件已清理")

    ids = []
    for i in range(3):
        rr = post("/api/moments", {"text": "T%d" % i, "keys": {"maa": "W"}}, ta).get_json()
        ids.append(rr["mid"])
    p1 = get("/api/moments?limit=2", ta).get_json()
    ok(len(p1["moments"]) == 2 and p1["has_more"] is True, "分页第一页 2 条 + has_more")
    p2 = get("/api/moments?limit=2&before=%d" % p1["moments"][-1]["created"], ta).get_json()
    ok(len(p2["moments"]) == 1 and p2["has_more"] is False, "分页第二页 1 条 + 到底")
finally:
    shutil.rmtree(TMP, ignore_errors=True)

print()
if FAILS:
    print("失败：", FAILS)
    sys.exit(1)
print("全部通过 ✅")
