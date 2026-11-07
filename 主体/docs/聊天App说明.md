# 灵珑聊天（v1.0）说明

## 一句话
家里自己用的聊天 App：**消息端到端加密**，NAS 只负责存密文和转发，服务器看不到聊天内容。

## 使用步骤

1. 手机装 `APK/linglum-chat-1.0.apk`（需要在同一 Wi-Fi，能访问 NAS）
2. 打开 App → 填**专属号**（如 `papa2026`，3-32 位字母数字下划线）+ **密码**（≥6 位）+ 昵称 → 点「注册并登录」
3. 第二台手机/另一位家人：同样注册自己的专属号
4. 在首页点「＋ 加好友」，输入对方专属号 → 找到后显示**安全码**（可当面核对）→ 添加
5. 点好友进入聊天，输入文字发送
6. 「＋ 建群」→ 填群名 + 勾选成员 → 创建，之后群聊里所有人都能收到

服务器地址默认 `http://192.168.1.100:8097`（只在局域网可用）。出门在外需要另配（NAS 公网入口/内网穿透），当前未做。

## 加密设计

```
身份密钥：EC P-256 密钥对（手机本地生成）
私钥保存：用密码派生密钥（PBKDF2-SHA256 20 万轮 → AES-256-GCM）加密后存 NAS
           → NAS 拿不到明文私钥，换手机登录也能恢复
单聊：AES-256-GCM，密钥 = SHA-256(ECDH(我的私钥, 对方公钥))
群聊：群主随机生成 32 字节群密钥，对每个成员用「与群主的 ECDH 密钥」加密后存 NAS；
      成员用自己私钥 + 群主公钥解开
```

服务端**只存**：专属号、昵称、公钥、加密后的私钥、密码的 PBKDF2 哈希、**消息密文**。

### 安全边界（诚实说明）
- ✅ NAS 看不到消息内容
- ⚠️ NAS 能看到元数据：谁、什么时候、给谁、多长的密文
- ⚠️ 私钥在手机 App 私有目录里（未 root 手机受系统保护），不是硬件级 Keystore
- ⚠️ 首次加好友有理论上的中间人风险（服务器换公钥）——App 显示安全码，建议当面核对一次
- ✅ 密码错、token 失效都返回 401；非群成员不能往群里发消息（服务端校验）

## 服务端

| 项 | 值 |
|---|---|
| 位置 | NAS `/vol1/1000/项目/安卓/chat/主体/server/` |
| 容器 | `linglong-chat`（`-p 8097:8097`，数据卷 `主体/server/data`） |
| 栈 | Flask 3 + gunicorn（gthread ×8）+ SQLite（WAL） |
| 数据 | `server/data/chat.db`（账号/消息/群） |
| 自测 | `python3 test_server.py`（22 项，Flask test client，临时库） |

重启：`sudo -n docker restart linglong-chat`；重建：
```sh
cd /vol1/1000/项目/安卓/server && sudo -n docker build -t linglong-chat:latest . \
  && sudo -n docker rm -f linglong-chat \
  && sudo -n docker run -d --name linglong-chat --restart unless-stopped \
     -p 8097:8097 -v /vol1/1000/项目/安卓/chat/主体/server/data:/data linglong-chat:latest
```

## App 端

| 项 | 值 |
|---|---|
| 源码 | `主体/app/`（MainActivity.java 界面 / Crypto.java 加解密 / Api.java HTTP） |
| 包名 | `com.linglum.chat`，minSdk 21 / targetSdk 34 |
| 重新打包 | `cd 主体/app && sh build_apk.sh` → `out/linglum-chat.apk` |
| 收发 | 每 3 秒轮询 `/api/messages?since=`（无推送） |
| 本地 | 消息追加存 `files/messages.jsonl`，好友/群存 SharedPreferences |

## 已知限制（v1）
- 只在局域网可用（服务器地址指 NAS 内网）
- 无图片/语音，只支持文字
- 无消息撤回/已读状态
- 群成员变更、退群暂不支持
- 无推送，App 在后台不提示
