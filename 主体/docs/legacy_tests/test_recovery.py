#!/usr/bin/env python3
"""找回二选一验证：A=只用恢复码，B=只用救援口令；两条路径各自独立完整跑一遍。"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8097"


def call(path, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-App-Version": "24"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


def kek(code, salt_hex):
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                      salt=bytes.fromhex(salt_hex), iterations=200000).derive(code.encode())


def enc(key, data):
    iv = os.urandom(12)
    return (base64.b64encode(iv).decode() + "."
            + base64.b64encode(AESGCM(key).encrypt(iv, data.encode(), None)).decode())


def dec(key, payload):
    iv, ct = payload.split(".")
    return AESGCM(key).decrypt(base64.b64decode(iv), base64.b64decode(ct), None).decode()


def setup(wxid, password):
    """注册一个带恢复码 + 救援口令的账号，返回 (私钥DER, 恢复码, 救援口令)"""
    priv = ec.generate_private_key(ec.SECP256R1())
    pub = base64.b64encode(priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    der = base64.b64encode(priv.private_bytes(
        serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption())).decode()
    pw_salt = os.urandom(16).hex()
    rescue = "9999AAAABBBBCCCCDDDDEEEEFFFF0000"
    rescue_salt = os.urandom(16).hex()
    st, r = call("/api/register", {
        "wxid": wxid, "password": password, "nickname": wxid, "pubkey": pub,
        "privkey_enc": enc(kek(password, pw_salt), der), "kdf_salt": pw_salt,
        "rescue_code": rescue, "rescue_kek_salt": rescue_salt,
        "privkey_rescue": enc(kek(rescue, rescue_salt), der),
    })
    assert st == 200, "注册失败 %s %s" % (st, r)
    return der, rescue


def reset_with(wxid, code, newpass):
    """用救援口令完成重置，返回 (prepare状态, 私钥解出?, finish状态)"""
    body = {"wxid": wxid, "rescue_code": code}
    st, pr = call("/api/recovery/prepare", body)
    if st != 200:
        return st, False, None
    try:
        priv_der = dec(kek(code, pr["kek_salt"]), pr["privkey_rescue"])
    except Exception:
        return st, False, None
    new_salt = os.urandom(16).hex()
    fin = {"wxid": wxid, "password": newpass,
           "privkey_enc": enc(kek(newpass, new_salt), priv_der), "kdf_salt": new_salt}
    fin.update({"rescue_code": code})
    st2, _ = call("/api/recovery/finish", fin)
    return st, True, st2


def main():
    fails = []

    # ---- A：用救援口令重置 ----
    der_a, rescue_a = setup("zzpatha", "oldpass1")
    st, okdec, st2 = reset_with("zzpatha", rescue_a, "newpass1")
    login = call("/api/login", {"wxid": "zzpatha", "password": "newpass1"})[0]
    old = call("/api/login", {"wxid": "zzpatha", "password": "oldpass1"})[0]
    print("A 用救援口令: prepare=%s 解密=%s finish=%s 新密码登录=%s 旧密码=%s"
          % (st, okdec, st2, login, old))
    if not (st == 200 and okdec and st2 == 200 and login == 200 and old == 401):
        fails.append("路径A")

    # ---- B：错误口令必须失败 ----
    der_b, rescue_b = setup("zzpathb", "oldpass2")
    st, okdec, st2 = reset_with("zzpathb", "0" * 32, "newpass2")
    login = call("/api/login", {"wxid": "zzpathb", "password": "newpass2"})[0]
    old = call("/api/login", {"wxid": "zzpathb", "password": "oldpass2"})[0]
    print("B 错误口令: prepare=%s（应 401，且不能重置）" % st)
    if st != 401:
        fails.append("反例")

    code = ("import sqlite3;c=sqlite3.connect('/data/chat.db')\n"
            "for u in ('zzpatha','zzpathb'):\n"
            "  c.execute('DELETE FROM tokens WHERE wxid=?',(u,))\n"
            "  c.execute('DELETE FROM users WHERE wxid=?',(u,))\n"
            "c.commit()")
    subprocess.run(["sudo", "-n", "docker", "exec", "linglong-chat",
                    "python3", "-c", code], capture_output=True)

    print("\n全部通过 ✅" if not fails else "\n失败: " + ", ".join(fails))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
