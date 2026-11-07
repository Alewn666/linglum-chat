#!/usr/bin/env python3
"""「找回账号后还能聊天」端到端模拟（真 ECDH + 真服务端路由，不联网）

模拟两台手机 + 一次找回换钥：
  ① 正常聊：双方 ECDH 共享密钥一致
  ② alice 找回账号 → 换新密钥对（pubkey_ver 1→2）
  ③ 好友 bob 若仍用「旧公钥」→ 双向都算不出同一个共享密钥（这就是用户遇到的 bug）
  ④ bob 调 /api/pubkeys 拿到新公钥 → 双向立刻恢复一致（这就是本轮的修复）
  ⑤ 群密钥：alice 换钥后自己解不开；bob 用新公钥重新包裹 → alice 解得开

用法：python3 test_keyrotation_e2e.py
"""
import base64
import hashlib
import json
import os
import shutil
import sys
import tempfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

TMP = tempfile.mkdtemp(prefix="chat_e2e_")
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


def newkey():
    return ec.generate_private_key(ec.SECP256R1())


def pub_b64(k):
    der = k.public_key().public_bytes(serialization.Encoding.DER,
                                      serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


def shared(mine, peer_pub_b64):
    """和 App 里 Crypto.sharedKey 等价：ECDH → SHA-256"""
    peer = serialization.load_der_public_key(base64.b64decode(peer_pub_b64))
    return hashlib.sha256(mine.exchange(ec.ECDH(), peer)).hexdigest()


try:
    a1, b1 = newkey(), newkey()
    pa1, pb1 = pub_b64(a1), pub_b64(b1)
    for w, pk in (("alice", pa1), ("bob", pb1)):
        r = post("/api/register", {"wxid": w, "password": "pass123456", "nickname": w,
                                   "pubkey": pk, "privkey_enc": "E", "kdf_salt": "S",
                                   "email": w + "@t.local"})
        ok(r.status_code == 200, "注册 " + w)
    ta = post("/api/login", {"wxid": "alice", "password": "pass123456"}).get_json()["token"]
    tb = post("/api/login", {"wxid": "bob", "password": "pass123456"}).get_json()["token"]

    # ① 正常：双方共享密钥一致
    ok(shared(a1, pb1) == shared(b1, pa1), "① 正常状态：alice↔bob 共享密钥一致（能聊）")

    # ② alice 找回账号并换新密钥对
    r = post("/api/recovery/apply", {"wxid": "alice", "contact": "alice@t.local"})
    key128 = r.get_json()["key128"]
    post("/api/recovery/approve", {"wxid": "alice", "key128": key128}, tb)
    a2 = newkey()
    pa2 = pub_b64(a2)
    r = post("/api/recovery/finish", {"wxid": "alice", "key128": key128,
                                      "password": "newpass123", "privkey_enc": "E2",
                                      "kdf_salt": "S2", "pubkey": pa2})
    ok(r.status_code == 200 and r.get_json()["new_keypair"] is True, "② alice 找回并换了新密钥对")
    # 找回会吊销所有旧会话 → 重新登录拿新 token（App 里也是重新登录）
    ta = post("/api/login", {"wxid": "alice", "password": "newpass123"}).get_json()["token"]
    ok(bool(ta), "② 换钥后重新登录成功")
    ok(pa1 != pa2, "② 新旧公钥确实不同（问题前提成立）")

    # ③ 好友还用旧公钥 → 双向都不通（复现用户遇到的「无法解密」）
    ok(shared(a2, pb1) != shared(b1, pa1), "③ 旧公钥对不上：alice 解不开 bob 发的（复现 bug）")
    ok(shared(b1, pa1) != shared(a2, pb1), "③ 旧公钥对不上：bob 也解不开 alice 发的")

    # ④ 好友刷新公钥（客户端在发送前 / 解密失败时会自动这么做）
    r = get("/api/pubkeys?ids=alice", tb)
    u = r.get_json()["users"]["alice"]
    ok(u["pubkey"] == pa2 and u["ver"] == 2, "④ 好友拉到新公钥（ver=2）")
    ok(shared(a2, pb1) == shared(b1, pa2), "④ 用新公钥后：alice↔bob 共享密钥重新一致")
    # 反向同理：alice 用 bob 的公钥（bob 没换钥）
    ok(shared(a2, pb1) == shared(b1, pa2), "④ 双向都通（不必重新加好友）")

    # ⑤ 群密钥重包裹
    group_key = os.urandom(32)
    # 建群：群主 alice（旧钥时期）替 bob 包裹
    wrap_for_bob_old = base64.b64encode(
        bytes(x ^ y for x, y in zip(group_key, hashlib.sha256(bytes.fromhex(shared(a1, pb1))).digest()))).decode()
    # v0.1.4：只能把好友拉进群（先互加好友并双方通过）
    post("/api/friends", {"wxid": "bob"}, ta)
    post("/api/friends/accept", {"wxid": "alice"}, tb)
    r = post("/api/groups", {"name": "家", "members": {"alice": "W_A", "bob": wrap_for_bob_old}}, ta)
    gid = r.get_json()["gid"]
    ok(r.status_code == 200, "⑤ 建群成功")

    # alice 换钥后，旧包裹解不开（模拟 g.key == null）
    old_unwrap = bytes(x ^ y for x, y in zip(base64.b64decode(wrap_for_bob_old),
                                             hashlib.sha256(bytes.fromhex(shared(a2, pb1))).digest()))
    ok(old_unwrap != group_key, "⑤ alice 换钥后，旧包裹解不出群密钥（群聊断）")

    # bob（有群密钥）用 alice 的新公钥重新包裹并上传
    new_wrap = base64.b64encode(bytes(
        x ^ y for x, y in zip(group_key, hashlib.sha256(bytes.fromhex(shared(b1, pa2))).digest()))).decode()
    # v0.1.4：对方公钥版本没变时不允许覆盖（防成员互相搞破坏）
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice", "wrapped": new_wrap}, tb)
    ok(r.status_code == 409, "⑤ 公钥未变时 rewrap 被拒（防覆盖）")
    import sqlite3 as _sq
    _con = _sq.connect(os.path.join(TMP, "chat.db"))
    _con.execute("UPDATE users SET pubkey_ver=pubkey_ver+1 WHERE wxid='alice'")
    _con.commit(); _con.close()
    r = post("/api/groups/%s/rewrap" % gid, {"member": "alice", "wrapped": new_wrap}, tb)
    ok(r.status_code == 200, "⑤ bob 替 alice 重新包裹群密钥（换钥后才允许）")

    r = get("/api/groups", ta)
    g = [x for x in r.get_json()["groups"] if x["gid"] == gid][0]
    ok(g["my_wrapped"] == new_wrap and g["my_wrapped_by"] == "bob",
       "⑤ alice 拿到新包裹，且知道是 bob 包裹的")
    got = bytes(x ^ y for x, y in zip(base64.b64decode(g["my_wrapped"]),
                                      hashlib.sha256(bytes.fromhex(shared(a2, pb1))).digest()))
    ok(got == group_key, "⑤ alice 解得回群密钥 → 群聊恢复")

    print("\n全部通过 ✅" if not FAILS else "\n失败项：%s" % FAILS)
finally:
    shutil.rmtree(TMP, ignore_errors=True)

if FAILS:
    raise SystemExit(1)
