# 灵珑聊天

安卓客户端 + NAS Flask 服务端的即时通讯应用：注册专属号、加好友、单聊/群聊，含端到端加密设计与服务端自测。

> 本仓库由 NAS 归档包整理发布。已剥离运行时数据、密钥、审计日志与大型二进制镜像；
> 文档中的真实口令与内网 IP 已脱敏。镜像请用仓库内构建脚本生成，或从 Releases 获取。

## 目录结构

```
├── APK/
├── README.md
├── 主体/
├── APK/linglum-chat-V0.1.1.apk
├── APK/linglum-chat-V0.1.2.apk
├── APK/linglum-chat-V0.1.3.apk
├── APK/linglum-chat-V0.1.4.apk
├── APK/linglum-chat-V0.1.5.apk
├── APK/linglum-chat-V0.2.0.apk
├── APK/linglum-chat-V0.2.1.apk
├── APK/linglum-chat-V0.2.2.apk
├── 主体/app/
├── 主体/docs/
├── 主体/server/
```

## 使用

请参考仓库内的说明文档与构建/部署脚本；首次运行前把示例地址、口令改成自己的值。

## 安全约定

- `data/`、`.env`、`env.*`、`*.pem`、`*.keystore`、`config.json`、`*.db`、`*.log` 均已在 `.gitignore` 中排除，切勿强制提交。
- 文档里的 `192.168.1.100` 与 `203.0.113.x` 是示例地址，`<REDACTED_PASSWORD>` 是脱敏占位符。
- 涉及管理后台的服务请只在可信局域网内使用。

## 许可证

MIT
