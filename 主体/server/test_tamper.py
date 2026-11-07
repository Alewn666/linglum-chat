#!/usr/bin/env python3
"""
灵珑聊天 —— 防篡改协议验证（模拟两个客户端，打真实服务端）
验证：签名正确时通过；改内容/改时间/改发件人/删消息 都能被检测出来。
用法：python3 test_tamper.py [服务地址，默认 http://127.0.0.1:8097]
"""
import base64
import hashlib
import json
import os
import sqlite3
import sys
import time
import urllib.request
import subprocess

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8097"
DB = "/vol1/1000/项目/安卓/chat/主体/server/data/chat.db"
U1, U2 = "zz_tamper_a", "zz_tamper_b"


def call(path, body=None, token=None):
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


def b64(b):
    return base64.b64encode(b).decode()


def sha256hex(s):
    return hashlib.sha256(s.encode()).hexdigest()


def canon(frm, tt, ti, ts, seq, prev, text):
    return "%s\n%s\n%s\n%d\n%d\n%s\n%s" % (frm, tt, ti, ts, seq, prev, sha256hex(text))


def keypair():
    k = ec.generate_private_key(ec.SECP256R1())
    pub = b64(k.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    return k, pub


def shared(a_priv, b_pub_b64):
    peer = serialization.load_der_public_key(base64.b64decode(b_pub_b64))
    return hashlib.sha256(a_priv.exchange(ec.ECDH(), peer)).digest()


def encrypt(key, text):
    iv = os.urandom(12)
    ct = AESGCM(key).encrypt(iv, text.encode(), None)
    return b64(iv) + "." + b64(ct)


def sign(priv, data):
    return b64(priv.sign(data.encode(), ec.ECDSA(hashes.SHA256())))


def verify(pub_b64, data, sig_b64):
    pk = serialization.load_der_public_key(base64.b64decode(pub_b64))
    try:
        pk.verify(base64.b64decode(sig_b64), data.encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False



def db_exec(sql, params=()):
    """通过容器内部执行 SQL（宿主机对该库只读）"""
    code = ("import sqlite3;con=sqlite3.connect('/data/chat.db');"
            "con.execute(%r, %r);con.commit();con.close()" % (sql, tuple(params)))
    subprocess.run(["sudo", "-n", "docker", "exec", "linglong-chat", "python3", "-c", code],
                   check=True, capture_output=True)


def db_value(sql, params=()):
    code = ("import sqlite3;con=sqlite3.connect('/data/chat.db');"
            "print(con.execute(%r, %r).fetchone()[0])" % (sql, tuple(params)))
    out = subprocess.run(["sudo", "-n", "docker", "exec", "linglong-chat", "python3", "-c", code],
                         capture_output=True, text=True, check=True)
    return out.stdout.strip()


def ok(cond, msg):
    print(("✅ " if cond else "❌ ") + msg)
    if not cond:
        raise SystemExit("失败: " + msg)


ka, pub_a = keypair()
kb, pub_b = keypair()
for u, pub in ((U1, pub_a), (U2, pub_b)):
    call("/api/register", {"wxid": u, "password": "zz123456", "nickname": u,
                           "pubkey": pub, "privkey_enc": "E", "kdf_salt": "S",
                           "email": u + "@t.local"})
ta = call("/api/login", {"wxid": U1, "password": "zz123456"})[1]["token"]
tb = call("/api/login", {"wxid": U2, "password": "zz123456"})[1]["token"]

kab = shared(ka, pub_b)

# ---- 正常发一条 ----
seq1, prev1, ts1, text1 = 1, "GENESIS", int(time.time() * 1000), "你好，这是一条测试"
env1 = json.dumps({"t": text1, "f": U1, "tt": "user", "ti": U2, "ts": ts1, "s": seq1, "p": prev1},
                  ensure_ascii=False)
sig1 = sign(ka, canon(U1, "user", U2, ts1, seq1, prev1, text1))
s, r = call("/api/messages", {"to_type": "user", "to_id": U2, "payload": encrypt(kab, env1),
                              "sig": sig1, "ts": ts1}, ta)
ok(s == 200, "发送带签名的消息")
mid1 = r["id"]

s, r = call("/api/messages?since=0", token=tb)
m = [x for x in r["messages"] if x["id"] == mid1][0]
raw = json.loads(AESGCM(kab).decrypt(base64.b64decode(m["payload"].split(".")[0]),
                                     base64.b64decode(m["payload"].split(".")[1]), None))
ok(raw["f"] == m["from"], "密文内发件人 == 服务端发件人")
ok(verify(pub_a, canon(raw["f"], m["to_type"], m["to_id"], raw["ts"], raw["s"], raw["p"], raw["t"]),
          m["sig"]), "正常消息签名验证通过")

# ---- 篡改 1：改消息内容（密文） ----
old = db_value("SELECT payload FROM messages WHERE id=?", (mid1,))
tampered = "A" + old[1:]
db_exec("UPDATE messages SET payload=? WHERE id=?", (tampered, mid1))
s, r = call("/api/messages?since=0", token=tb)
m = [x for x in r["messages"] if x["id"] == mid1][0]
broken = False
try:
    iv, ct = m["payload"].split(".")
    AESGCM(kab).decrypt(base64.b64decode(iv), base64.b64decode(ct), None)
except Exception:
    broken = True
ok(broken, "改密文 → GCM 认证失败（解不开）")
db_exec("UPDATE messages SET payload=? WHERE id=?", (old, mid1))

# ---- 篡改 2：改时间戳 ----
db_exec("UPDATE messages SET ts=? WHERE id=?", (ts1 + 999, mid1))
s, r = call("/api/messages?since=0", token=tb)
m = [x for x in r["messages"] if x["id"] == mid1][0]
raw = json.loads(AESGCM(kab).decrypt(base64.b64decode(m["payload"].split(".")[0]),
                                     base64.b64decode(m["payload"].split(".")[1]), None))
ok(not verify(pub_a, canon(raw["f"], m["to_type"], m["to_id"], m["ts"], raw["s"], raw["p"], raw["t"]),
              m["sig"]), "改服务端时间戳 → 签名失败（被检测）")
db_exec("UPDATE messages SET ts=? WHERE id=?", (ts1, mid1))

# ---- 篡改 3：改发件人 ----
db_exec("UPDATE messages SET from_wxid=? WHERE id=?", (U2, mid1))
s, r = call("/api/messages?since=0", token=tb)
m = [x for x in r["messages"] if x["id"] == mid1][0]
raw = json.loads(AESGCM(kab).decrypt(base64.b64decode(m["payload"].split(".")[0]),
                                     base64.b64decode(m["payload"].split(".")[1]), None))
ok(raw["f"] != m["from"], "改服务端发件人 → 与密文内发件人不符（被检测）")
db_exec("UPDATE messages SET from_wxid=? WHERE id=?", (U1, mid1))

# ---- 消息链：发第二条，再删掉第一条 ----
seq2, prev2, ts2, text2 = 2, sha256hex(sig1), int(time.time() * 1000), "第二条消息"
env2 = json.dumps({"t": text2, "f": U1, "tt": "user", "ti": U2, "ts": ts2, "s": seq2, "p": prev2},
                  ensure_ascii=False)
sig2 = sign(ka, canon(U1, "user", U2, ts2, seq2, prev2, text2))
s, r = call("/api/messages", {"to_type": "user", "to_id": U2, "payload": encrypt(kab, env2),
                              "sig": sig2, "ts": ts2}, ta)
mid2 = r["id"]
db_exec("DELETE FROM messages WHERE id=?", (mid1,))
s, r = call("/api/messages?since=0", token=tb)
m2 = [x for x in r["messages"] if x["id"] == mid2][0]
raw2 = json.loads(AESGCM(kab).decrypt(base64.b64decode(m2["payload"].split(".")[0]),
                                      base64.b64decode(m2["payload"].split(".")[1]), None))
chain_broken = raw2["s"] != 1 or raw2["p"] != "GENESIS"
ok(chain_broken, "删掉前一条 → 链断开（被检测）")

# ---- 清理 ----
db_exec("DELETE FROM messages WHERE from_wxid IN (?,?)", (U1, U2))
db_exec("DELETE FROM tokens WHERE wxid IN (?,?)", (U1, U2))
db_exec("DELETE FROM users WHERE wxid IN (?,?)", (U1, U2))
print("\n全部通过 ✅（测试数据已清理）")
