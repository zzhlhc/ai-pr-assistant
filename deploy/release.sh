#!/usr/bin/env bash
# 本地发布：构建 → 传输 → 重启。
#
# 用法：./deploy/release.sh root@1.2.3.4
#
# 为什么不直接在服务器上构建：2 核跑一遍 Maven + npm 要吃满 CPU 好几分钟，
# 还容易 OOM。本地构建完只传产物，服务器上永远不装 JDK 编译器之外的构建工具。
set -euo pipefail

HOST=${1:?用法: ./deploy/release.sh root@<服务器IP>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

echo "==> 1/4 打包后端"
mvn -B -q clean package -DskipTests
JAR=$(ls target/*.jar | head -1)
echo "产物：$JAR"

echo "==> 2/4 构建前端"
(cd web && npm run build)

echo "==> 3/4 传输"
scp -q "$JAR" "$HOST:/opt/ai-pr/app.jar"
# 先清空再拷贝：vite 每次构建的 assets 文件名带 hash，旧文件不删会越堆越多
ssh "$HOST" 'rm -rf /var/www/ai-pr/*'
scp -qr web/dist/. "$HOST:/var/www/ai-pr/"

echo "==> 4/4 重启并检查"
ssh "$HOST" 'systemctl restart ai-pr'
# 启动要初始化 Spring 容器，给几秒钟再判定
sleep 8
ssh "$HOST" 'systemctl is-active ai-pr && curl -sf localhost/actuator/health && echo'
echo "发布完成：http://${HOST#*@}/"
