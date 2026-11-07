#!/usr/bin/env python3
"""撤回消息自测（V0.0.11）"""
import json, os, shutil, sys, tempfile, time
TMP = tempfile.mkdtemp(prefix="chat_recall_"); os.environ["CHAT_DATA_DIR"] = TMP
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as srv
srv.init_db(); c = srv.app.test_client(); FAILS = []


def ok(cond, msg):
    print(("✅ " if cond else "❌ ") + msg)
    if not cond:
        FAILS.append(msg)


def post(p, b, t=None):
    h = {"Authorization": "Bearer " + t} if t else {}
    return c.post(p, data=json.dumps(b), content_type="application/json", headers=h)


def get(p, t=None):
    return c.get(p, headers={"Authorization": "Bearer " + t} if t else {})


try:
    for w in ("sender1", "recver1"):
        post("/api/register", {"wxid": w, "password": "pass123456", "nickname": w,
                               "pubkey": "PK", "privkey_enc": "E", "kdf_salt": "S",
                               "email": w + "@t.local"})
    ts = post("/api/login", {"wxid": "sender1", "password": "pass123456"}).get_json()["token"]
    tr = post("/api/login", {"wxid": "recver1", "password": "pass123456"}).get_json()["token"]

    mid = post("/api/messages", {"to_type": "user", "to_id": "recver1",
                                "payload": "CIPHER", "ts": int(time.time() * 1000)}, ts).get_json()["id"]
    ok(get("/api/messages?since=0", tr).get_json()["messages"][0]["recalled"] == 0, "新消息 recalled=0")

    ok(post("/api/messages/%d/recall" % mid, {}, tr).status_code == 403, "别人不能撤回我的消息（403）")
    ok(post("/api/messages/9999999/recall", {}, ts).status_code == 404, "不存在的消息 404")
    ok(post("/api/messages/%d/recall" % mid, {}, ts).status_code == 200, "发件人撤回成功")
    ok(post("/api/messages/%d/recall" % mid, {}, ts).get_json().get("already") is True, "重复撤回幂等")

    ms = get("/api/messages?since=0", tr).get_json()["messages"]
    ok(ms[0]["recalled"] == 1, "收件人拉到 recalled=1（只显示「撤回了一条消息」）")

    # 超过 2 分钟
    old_mid = post("/api/messages", {"to_type": "user", "to_id": "recver1",
                                     "payload": "OLD", "ts": int(time.time() * 1000) - 300000}, ts).get_json()["id"]
    ok(post("/api/messages/%d/recall" % old_mid, {}, ts).status_code == 400, "超过 2 分钟不能撤回（400）")

    print("\n全部通过 ✅" if not FAILS else "\n失败：%s" % FAILS)
finally:
    shutil.rmtree(TMP, ignore_errors=True)
if FAILS:
    raise SystemExit(1)
