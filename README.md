# 💬 灵珑聊天

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Release](https://img.shields.io/github/v/release/Alewn666/linglum-chat?label=release)
![E2EE](https://img.shields.io/badge/%E6%B6%88%E6%81%AF-%E7%AB%AF%E5%88%B0%E7%AB%AF%E5%8A%A0%E5%AF%86-2F855A)

> 给家里几个人自己用的聊天 App：**消息端到端加密，NAS 只存密文、只负责转发，服务器看不到聊天内容**。安卓客户端 + 自建 Flask 服务端，不用任何第三方账号。

注册一个专属号（比如 `papa2026`），加好友时当面核对一次安全码，就能单聊、建群、发消息、撤回，还有朋友圈和资料页。密钥在手机本地生成，服务端数据库里躺着的只是公钥、加密后的私钥、密码哈希和消息密文。

和「装个现成 IM」不同，这个项目把密钥体系、热更新、构建工具链都自己做了一遍，而且是在一台 **2 GB 内存的 ARM NAS** 上做的——所以你能看到很多针对真实资源限制的设计，比如编译前的内存护栏、不依赖 Gradle 的命令行打包。

## 加密设计（以及它的边界）

```
身份密钥    EC P-256 密钥对，手机本地生成
私钥保管    密码 → PBKDF2-SHA256 20 万轮 → AES-256-GCM 加密后存 NAS
            → NAS 拿不到明文私钥，换手机登录还能恢复
单聊        AES-256-GCM，密钥 = SHA-256(ECDH(我的私钥, 对方公钥))
群聊        群主随机生成 32 字节群密钥，按每个成员的 ECDH 密钥分别加密后存 NAS
```

服务端**只存**：专属号、昵称、公钥、加密后的私钥、密码的 PBKDF2 哈希、消息密文。

| 能做到 | 做不到（明说） |
| --- | --- |
| NAS 看不到消息内容 | NAS 能看到元数据：谁、何时、发给谁、密文多长 |
| 密码错 / token 失效返回 401，非群成员不能往群里发消息 | 私钥在 App 私有目录，不是硬件级 Keystore |
| 加好友显示安全码，可当面核对 | 首次加好友理论上存在服务器换公钥的中间人风险（所以建议当面核对一次） |

完整的加密说明见 [聊天App说明.md](主体/docs/聊天App说明.md)。

## 功能

| 模块 | 内容 |
| --- | --- |
| 账号 | 专属号注册/登录、昵称与个人资料、密码校验 |
| 关系 | 加好友（扫码 / 搜专属号）、好友列表、安全码核对 |
| 消息 | 单聊、群聊、消息撤回、离线拉取 |
| 动态 | 朋友圈发布与查看 |
| 密钥 | 密钥同步、密钥轮换（含端到端测试） |
| 更新 | 补丁热更新 + 整包更新 / APK 内自更新 |
| 界面 | Material 风格、深色主题、底部 Tab、扫码 |

## 自研热更新：不用每次都重装 APK

壳只保留清单里必须写死的组件（`ChatService`、`QrScan`、两个 Provider、`HotLoader`），真正的界面和业务逻辑放在可替换的 `ChatApp` 里：

```
安装包（薄壳，很少变）          补丁（几十 KB，经常变）
├─ MainActivity            →   └─ ChatApp（全部界面/业务逻辑）
├─ HotLoader（加载+验签+回滚）
└─ Api / Crypto / 数据模型
```

启动时 `HotLoader.load()` 找 `filesDir/patch/classes.dex`：**验签通过**才用 `DexClassLoader` 加载补丁；验签失败或连续两次启动失败自动回落到内置版本。补丁私钥不进仓库，公钥烧在壳里，所以换密钥就必须发新壳。跨 ClassLoader 只通过 `AppCore` 接口交互。

发一个补丁：

```bash
cd 主体/app
sh tools/quickjavac.sh                       # 先确认能编译
sh build_patch.sh 2 "这次改了什么" 7         # 补丁号 / 说明 / minShell
sh publish_patch.sh out/patch               # 发布到服务端 data/patch/
```

细节见 [热更新说明.md](主体/docs/热更新说明.md)。

## 快速开始

**服务端**（Flask 3 + gunicorn gthread 32 线程 + SQLite WAL，默认 8097）：

```bash
cd 主体/server
pip install -r requirements.txt
python3 app.py                       # 或按 Dockerfile 用 gunicorn 起
# docker build -t linglum-chat-server . && docker run -p 8097:8097 linglum-chat-server
```

> 仓库内 `主体/docs/聊天App说明.md` 是 v1.0 时期的说明，其中「gthread ×8」与当前 Dockerfile 的 **32 线程**不一致，部署以 Dockerfile 为准。

**客户端**：直接装 [Releases](https://github.com/Alewn666/linglum-chat/releases) 里的 `linglum-chat-V0.3.3.apk`，或者从源码构建：

```bash
cd 主体/app
sh build_apk.sh                      # 无 Gradle：javac → d8 → aapt2 → zipalign → apksigner
```

App 里把服务器地址填成 NAS 的局域网地址（默认示例 `http://192.168.1.100:8097`，请改成自己的）。`AndroidManifest.xml` 声明 `minSdkVersion=21`（Android 5.0+）、`targetSdkVersion=34`，构建脚本用 `--min-api 21`。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8097` | 服务端端口 |
| `CHAT_DATA_DIR` | `./data` | SQLite 数据库与补丁目录 |
| `CHAT_PEPPER` | — | 密码哈希的额外 pepper，建议设置 |
| `CHAT_TLS_CERT` / `CHAT_TLS_KEY` | — | 同时提供时服务端直接以 HTTPS 启动 |

## 在 2 GB 内存的 NAS 上构建

`build_apk.sh` 里有两处很实在的护栏：可用内存低于 600 MB 直接拒绝编译（否则 qemu 版 aapt2 会把整机拖进 OOM），以及资源/第三方库编译缓存。曾经尝试引入 AndroidX / Material3 的 AAR 资源合并，因为 29 个库资源连续编译会 OOM 而**主动放弃并清理**，脚本回到了「只用自己的代码 + zxing」的干净状态。这段取舍写在脚本注释里。

## 测试

服务端带 5 组基于 Flask test client 的端到端测试（临时库，不碰真实数据）：

```bash
cd 主体/server
python3 test_keysync.py
python3 test_keyrotation_e2e.py
python3 test_moments.py
python3 test_profile_friends.py
python3 test_recall.py
```

## 目录结构

```
linglum-chat/
├── APK/                     # V0.1.1 → V0.3.3 共 19 个历史版本安装包
├── 主体/
│   ├── app/                 # 安卓客户端源码与构建脚本
│   │   ├── src/com/linglum/chat/   # HotLoader / Crypto / Api / ChatApp ...
│   │   ├── build_apk.sh / build_patch.sh / publish_patch.sh
│   │   └── res/ AndroidManifest.xml
│   ├── server/              # Flask 服务端（约 2100 行）、Dockerfile、测试
│   └── docs/                # 加密、热更新、昵称校验、防误改等设计文档
└── README.md
```

## 已知的边界

- 服务器地址默认指向局域网；**出门在外需要自己配公网入口或内网穿透，当前未做**。
- 端到端加密不等于匿名：元数据对服务器可见，安全边界见上表。
- 仓库内 `APK/` 保留了完整版本迭代历史，最新版见 Releases 附件。私钥、数据库、审计日志与真实口令均未纳入公开仓库。

## 许可证

MIT — 见 [LICENSE](LICENSE)。本项目由 NAS 归档包整理后公开发布。
