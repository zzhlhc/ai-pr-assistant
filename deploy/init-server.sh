#!/usr/bin/env bash
# 服务器首次初始化（Ubuntu 22.04 / 24.04，用 root 执行）。
# 幂等：重复执行不会出错。
#
# 用法：
#   1. 本地 scp -r deploy root@<IP>:/root/
#   2. 服务器上 cp /root/deploy/env.example /etc/ai-pr/env 并填好，chmod 600
#   3. 服务器上 bash /root/deploy/init-server.sh
set -euo pipefail

ENV_FILE=/etc/ai-pr/env
APP_DIR=/opt/ai-pr
WEB_DIR=/var/www/ai-pr
RUN_USER=ai-pr

if [ "$(id -u)" -ne 0 ]; then
    echo "请用 root 执行：sudo bash init-server.sh" >&2
    exit 1
fi
if [ ! -f "$ENV_FILE" ]; then
    echo "$ENV_FILE 不存在。先 cp deploy/env.example $ENV_FILE 并填好内容再执行。" >&2
    exit 1
fi

# 从 env 文件里取数据库账号，避免同一个密码写两遍
DB_USERNAME=$(grep -E '^DB_USERNAME=' "$ENV_FILE" | cut -d= -f2-)
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
if [ -z "$DB_USERNAME" ] || [ -z "$DB_PASSWORD" ]; then
    echo "$ENV_FILE 里的 DB_USERNAME / DB_PASSWORD 不能为空。" >&2
    exit 1
fi

echo "==> 1/8 配置 2G swap"
# 4G 内存一旦被 OOM Killer 挑中，进程是直接死的，没有任何日志，swap 是廉价保险
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "已创建 /swapfile"
else
    echo "已存在，跳过"
fi

echo "==> 2/8 安装 JDK 21 / MySQL / nginx"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq mysql-server nginx rsync

# Ubuntu 22.04 的源里只有 openjdk-17，没有 21；24.04 才有。
# 这里不自动加第三方源（要装 GPG key，脚本会变复杂），直接把出路打出来。
if ! apt-get install -y -qq openjdk-21-jre-headless; then
    cat >&2 <<'TIP'
本系统的 apt 源里没有 openjdk-21-jre-headless。
两个选择：
  1) 换用 Ubuntu 24.04（推荐，源里自带 21）
  2) 留在 22.04，手动加 Temurin 源后再跑一次本脚本：
       apt-get install -y wget apt-transport-https gnupg
       wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
         | gpg --dearmor | tee /usr/share/keyrings/adoptium.gpg >/dev/null
       echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
         > /etc/apt/sources.list.d/adoptium.list
       apt-get update && apt-get install -y temurin-21-jre
TIP
    exit 1
fi

echo "==> 3/8 MySQL 内存参数"
cp "$(dirname "$0")/mysql-tuning.cnf" /etc/mysql/mysql.conf.d/ai-pr.cnf
systemctl restart mysql
systemctl enable mysql >/dev/null

echo "==> 4/8 建库建账号"
# 表结构不在这里建：应用启动时会跑 schema.sql 建表，那份脚本是幂等的
# localhost 和 127.0.0.1 在 MySQL 里是两个不同的 host：
# 命令行 mysql 走 unix socket 匹配 localhost，JDBC 走 TCP 到 127.0.0.1。
# 默认配置下反解会把它归到 localhost，但一旦服务器开了 skip_name_resolve 就匹配不上，
# 所以两个都建，省得排查"Access denied"。
mysql <<SQL
CREATE DATABASE IF NOT EXISTS ai_pr_assistant DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USERNAME}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${DB_USERNAME}'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
ALTER USER '${DB_USERNAME}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
ALTER USER '${DB_USERNAME}'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON ai_pr_assistant.* TO '${DB_USERNAME}'@'localhost';
GRANT ALL PRIVILEGES ON ai_pr_assistant.* TO '${DB_USERNAME}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo "==> 5/8 建运行用户和目录"
id -u "$RUN_USER" >/dev/null 2>&1 || useradd -r -d "$APP_DIR" -s /usr/sbin/nologin "$RUN_USER"
# logs 目录一起建好并归属运行用户：应用是按天滚动写日志文件的
mkdir -p "$APP_DIR" "$APP_DIR/logs" "$WEB_DIR"
chown "$RUN_USER:$RUN_USER" "$APP_DIR"
chown -R www-data:www-data "$WEB_DIR"

echo "==> 6/8 nginx 站点"
cp "$(dirname "$0")/nginx.conf" /etc/nginx/sites-available/ai-pr
ln -sf /etc/nginx/sites-available/ai-pr /etc/nginx/sites-enabled/ai-pr
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx
systemctl enable nginx >/dev/null

echo "==> 7/8 systemd 服务"
cp "$(dirname "$0")/ai-pr-assistant.service" /etc/systemd/system/ai-pr.service
chmod 600 "$ENV_FILE"
systemctl daemon-reload
systemctl enable ai-pr >/dev/null

echo "==> 8/8 限制 journal 占用"
# 默认不限制，日志能一路涨到把磁盘写满
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/ai-pr.conf <<'EOF'
[Journal]
SystemMaxUse=200M
EOF
systemctl restart systemd-journald

if command -v ufw >/dev/null 2>&1; then
    ufw allow 80/tcp >/dev/null 2>&1 || true
fi

echo
echo "初始化完成。下一步在本地执行：./deploy/release.sh root@<服务器IP>"
