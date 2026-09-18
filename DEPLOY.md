# 部署说明（2C4G 单机 · 不用 Docker）

## 一、为什么不用 Docker，以及它的真实代价

Docker 在单机上确实有开销：`dockerd` + `containerd` 常驻约 100~150MB。这个项目只有一个应用、一个数据库、一个静态站点，没有多租户也没有环境冲突，隔离带来的收益基本为零，所以省掉这 150MB 是划算的。

代价是要自己管四件事：MySQL 建库建账号、JVM 用 systemd 托管、日志轮转、开机自启。这些都已经写成脚本放在 `deploy/` 下，一次性配好后不用再碰。

调整后的内存账（4 GB）：

| 组件 | 内存 | 说明 |
| --- | --- | --- |
| 系统 + sshd | ~400 MB | Ubuntu 基础占用 |
| MySQL | ~300~400 MB | 已关 `performance_schema`（省约 200MB） |
| JVM 应用 | ~800 MB | `-Xmx512m`，另加元空间 / 线程栈 / 直接内存 |
| nginx | ~20 MB | 静态文件 + 反代 |
| **合计** | **~1.6 GB** | 剩约 2.4 GB 余量 |

CPU 不是瓶颈：一次评审 60~90 秒，其中 99% 是等 DeepSeek 返回的网络等待，2 核基本闲着。评审线程池本来就写死 `core=2 / max=2`（`ReviewExecutorConfig`），正好匹配 2 核。

**真正会卡住的不是运行，是构建。** Maven + npm 在 2C4G 上吃满 2 核好几分钟还容易 OOM，所以走「本地构建 → 只传产物到服务器」，服务器上不装构建工具。

## 二、部署结构

```
浏览器 ──:80──> nginx
                 ├── /           读 /var/www/ai-pr 下的静态文件，SPA 回退到 index.html
                 └── /api/*      反代到 127.0.0.1:8080
                                      │
                                 systemd: ai-pr.service
                                 /usr/bin/java -Xmx512m -jar /opt/ai-pr/app.jar
                                      │
                                      └──> MySQL 127.0.0.1:3306 / ai_pr_assistant
```

前后端同源，前端 `axios` 的 `baseURL: '/api'` 不用改，也不涉及跨域。

## 三、服务器初始化（只做一次）

买一台 2C4G 的轻量应用服务器。**系统选 Ubuntu 24.04** —— 它的源里自带 `openjdk-21-jre-headless`，而 22.04 只有 17，得额外加 Temurin 源（`init-server.sh` 里已把命令打出来）。

```bash
# 1. 本地把 deploy 目录和改好的 env 传上去
ssh root@<服务器IP> 'mkdir -p /etc/ai-pr'
scp -r deploy root@<服务器IP>:/root/
cp deploy/env.example /tmp/env && vim /tmp/env        # 填密码和密钥
scp /tmp/env root@<服务器IP>:/etc/ai-pr/env

# 2. 服务器上执行初始化
ssh root@<服务器IP>
chmod 600 /etc/ai-pr/env
bash /root/deploy/init-server.sh
```

脚本会依次完成：配 2G swap、装 JDK 21 / MySQL / nginx、写 MySQL 内存参数、建库建账号、建运行用户和目录、配 nginx 站点、装 systemd 服务、限制 journal 占用。脚本是幂等的，中途失败可以改完再跑一次。

几点说明：

- **swap 不是可选项。** 4 GB 内存一旦被 OOM Killer 挑中，进程是直接死的，日志里什么都没有。
- **表和索引不在初始化里建。** 应用启动时会执行 `src/main/resources/schema.sql`（`spring.sql.init.mode=always`），脚本全是 `IF NOT EXISTS`，重启重复执行也安全。
- **`/etc/ai-pr/env` 属主是 root、权限 600**，而进程以 `ai-pr` 普通用户跑。这样即使 jar 或进程被拿到，也读不到 LLM Key 和数据库密码。
- 国内服务器**用 IP 访问不需要备案**，绑域名走 80 端口才需要。
- JDBC 连的是 `127.0.0.1:3306`，命令行 `mysql` 走的是 socket。MySQL 里这是两个不同的 host，脚本把账号建了两遍（`localhost` 和 `127.0.0.1`），省得之后排查 `Access denied`。

## 四、发布

```bash
./deploy/release.sh root@<服务器IP>
```

脚本做四件事：`mvn package` 打包 → `npm run build` 构建前端 → `scp` 传 jar 和 dist → 重启服务并检查 `/actuator/health`。

传输前会先清空 `/var/www/ai-pr`：vite 每次构建的 assets 文件名带内容 hash，不清空旧文件会一直堆积。

## 五、验证

```bash
curl -s localhost/actuator/health     # {"status":"UP"}
curl -s localhost/api/stats           # 能返回 JSON 说明 nginx → 应用 → MySQL 整条链路通了
```

然后浏览器打开 `http://<服务器IP>/`，提交一个真实 commit 跑一次。首次启动要初始化 Spring 容器并建表，约 30~60 秒。

## 六、排查

```bash
journalctl -u ai-pr -f                                  # 应用日志
journalctl -u ai-pr --since "10 min ago" | tail -50
ls -lh /opt/ai-pr/logs                                  # 落盘日志，一天一个文件
tail -f /opt/ai-pr/logs/ai-pr-assistant.log
systemctl status ai-pr mysql nginx
tail -f /var/log/nginx/error.log

# 直接查库
mysql -uai_pr -p ai_pr_assistant -e "select id,repo,status,issue_count,cost,created_at from review_task order by created_at desc limit 5"

free -h                 # 看内存和 swap 实际占用
systemd-cgtop           # 看每个服务的 CPU / 内存
```

日志已经限制过：应用日志按天落盘到 `/opt/ai-pr/logs`，保留 30 天、总量封顶 1G，
同时仍走 journal（上限 200M），nginx 走 `logrotate`（发行版自带）。

## 七、更新版本

```bash
./deploy/release.sh root@<服务器IP>       # 就这一条
```

数据在 `/var/lib/mysql`，更新只替换 jar 和静态文件，不动数据库。

## 八、已知取舍

| 项 | 说明 |
| --- | --- |
| 不上 HTTPS | 需要域名才能签证书。用 IP 访问先 HTTP；要加就上 Caddy 自动签证书，比 certbot 省事 |
| 不做限流 | 公网暴露后任何人都能提交任务，一次评审约 0.1~0.5 元。演示期间盯着 `/api/stats`，被刷就 `systemctl stop ai-pr` |
| 服务器上不构建 | 2 核跑 Maven + npm 太慢且容易 OOM，所以本地构建、只传产物 |
| 不做多实例 | 任务进度存在单个 JVM 的内存里（`ReviewTaskStore`），app 只能有一个实例，不能水平扩容 |
| 不配 CI | 个人项目没必要；要加就是 GitHub Actions 构建后 `scp`，与 `release.sh` 同样的步骤 |
