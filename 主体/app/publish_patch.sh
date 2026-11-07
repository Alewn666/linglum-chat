#!/bin/sh
# 发布热更新补丁到服务端（数据目录 data/patch/）
# 用法： sh publish_patch.sh /path/to/out/patch   （含 patch-<v>.dex、.sig、version.json）
set -e
SRC="$1"
[ -d "$SRC" ] || { echo "用法: sh publish_patch.sh <out/patch 目录>"; exit 1; }
DST="/vol1/1000/项目/安卓/chat/主体/server/data/patch"
sudo -n mkdir -p "$DST"
sudo -n cp "$SRC"/version.json "$DST"/version.json
for f in "$SRC"/patch-*.dex; do [ -f "$f" ] && sudo -n cp "$f" "$DST"/; done
sudo -n chown -R 1000:1001 "$DST" 2>/dev/null || true
echo "== 已发布到 $DST =="
sudo -n ls -la "$DST"
echo "== 线上接口 =="
curl -s --max-time 6 http://127.0.0.1:8097/api/patch/latest | head -c 400; echo
