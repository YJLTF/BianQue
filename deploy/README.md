# deploy（部署）· v1.1.1

扁鹊平台部署形态：

| 形态 | 适用 | 入口 |
|---|---|---|
| ⓪ 离线交付包部署 | 目标机无构建条件（内网常用） | `dist/bianque-images-1.1.1.tar.gz` + 本目录 |
| ① 平台全容器一键部署 | 生产/演示服务器（只需 Docker） | [`docker-compose.prod.yml`](docker-compose.prod.yml) + [`.env.example`](.env.example) |
| ② 节点探针 · Docker | Linux 目标主机（推荐，与平台镜像同源） | [`Dockerfile.probe`](docker/Dockerfile.probe) |
| ③ 节点探针 · 裸机 systemd | 已装 JDK 11+ 的 Linux 主机 | [`probe/install.sh`](probe/install.sh) |

开发者在宿主机直跑后端/前端时，只需基础设施层 [`docker-compose.yml`](docker-compose.yml)（MySQL + Redis）。
使用指南（页面操作/工作流/FAQ）见 [`docs/USAGE.md`](../docs/USAGE.md)。

---

## ⓪ 离线交付包部署（无需构建）

交付包随版本发布（构建机执行 `docker save` 导出，见根 README「发布交付」）：

```bash
docker load -i bianque-images-1.1.1.tar.gz    # 导入 bianque/server|web|probe:1.1.1 + mysql:8.4 + redis:7.4
cd deploy
cp .env.example .env                          # 按需修改（BQ_VERSION 保持 1.1.1；生产必改令牌/口令）
docker compose -f docker-compose.prod.yml up -d   # 镜像已就位，无需 --build
```

## ① 平台一键部署（全容器）

**架构**：`web`(Nginx 静态 + 反代) → `server`(Spring Boot) → `mysql` / `redis`；目标主机另装探针（见下节）回连注册。

```
浏览器 ──80──> bq-web(Nginx) ──/api /docs──> bq-server:8080 ──> mysql / redis
目标主机探针 bq-agent ──出站 HTTP(5s 心跳)──> http://<平台>:8080/api/agent/**
```

**前置**：Docker 20+ 与 Docker Compose v2；无需 JDK/Node（镜像内多阶段构建）。

```bash
cd deploy
cp .env.example .env                # 修改 AGENT_TOKEN 等配置（compose 自动读取）
docker compose -f docker-compose.prod.yml up -d --build
# 首次构建需联网拉取 Maven/npm 依赖，约 5~15 分钟；之后命中缓存秒级
```

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `BQ_VERSION` | 1.1.1 | 平台镜像 tag（升级版本时同步修改；离线部署需先 load 对应镜像） |
| `AGENT_TOKEN` | 空 | 节点接入令牌（`X-Agent-Token`）；**生产模式必配**（未配置服务端拒绝启动），需与探针侧一致 |
| `INGEST_TOKEN` | 空（关闭） | 告警接入令牌（`X-Alert-Token`），配置后外部告警系统可推入 `POST /api/alerts/ingest` |
| `LLM_BASE_URL` | 空 | AI 问诊端点预置（OpenAI 兼容，不含 `/chat/completions` 尾巴；也可部署后在「AI 问诊」页填写） |
| `LLM_API_KEY` | 空 | AI 问诊 LLM 密钥预置（落库存储、只回传掩码） |
| `BIANQUE_DB_PASSWORD` | bianque123 | MySQL 业务账号密码（仅内网演示默认值）。**仅在数据卷首次初始化时生效**，已初始化的环境改密码用 `ALTER USER` 或删卷重来；密码由 compose 同时注入 MySQL 与服务端，两侧自动一致 |
| `MYSQL_ROOT_PASSWORD` | bianque_dev_only | MySQL root 密码（仅内网演示默认值） |
| `WEB_PORT` | 80 | 前端对外端口 |
| `PROBE_NAME` | hostname | `--profile probe` 演示节点显示名 |
| `PROBE_INTERVAL` | 5 | `--profile probe` 演示节点心跳间隔（秒） |
| `BIANQUE_DEMO_ENABLED` | false | 服务端演示模式开关；true=模拟靶场/种子告警/演示账号，false（prod 默认，生产基线）=仅真实探针实例 |
| `BIANQUE_ADMIN_PASSWORD` | 空 | 生产模式引导 admin 初始口令（仅 admin 不存在时生效一次）；演示模式无需。**生产模式必填** |

**验证**：

```bash
curl http://<主机>/api/health                 # {"app":"bianque-server","version":"1.1.1","status":"UP",...}
# 浏览器打开 http://<主机>/ → 节点管理页
# 登录：演示模式 admin/admin123；生产模式用 BIANQUE_ADMIN_PASSWORD 设置的口令
```

**说明**：

- 容器化 server **自动关闭本机纳管通道**（`bianque.execution.local-enabled=false`）——容器内看不到宿主 JVM，
  真实诊断一律由目标主机探针回连提供；拟真靶场、审计、巡检、AI 问诊等全功能可用
- **`GET /api/agent/download` 开箱即用**：bq-agent.jar 已打入镜像（/app/agents/），节点接入指引的
  下载步骤无需手工放置
- 数据落 MySQL volume（`mysql-data`），重启/升级不丢；server 日志与临时文件在 `server-data` volume
- 升级：修改 `.env` 的 `BQ_VERSION` → `docker compose -f docker-compose.prod.yml up -d --build`
  （DDL 自动增量，向后兼容）
- 备份：`docker exec bq-mysql sh -c "mysqldump -ubianque -p\$MYSQL_PASSWORD bianque" > backup.sql`
- TLS：镜像内不做 TLS，生产建议外层 Nginx/网关统一终结 HTTPS 后反代到 `WEB_PORT`

## ② 节点探针 · Docker（Linux 主机推荐）

探针镜像内置 `bq-agent.jar` + 完整 arthas 发行目录（`/probe/arthas/`，attach 显式 --arthas-home：
离线免下载、对目标 JVM 用户可读，root 探针可 attach 其它用户的应用）。**必须** `--pid=host` 并挂载 `/tmp`
（JVM attach 依赖宿主 `/tmp` 下的 hsperfdata/attach socket），探针以 root 运行可附加 root 启动的 JVM：

```bash
docker build -f deploy/docker/Dockerfile.probe -t bianque/probe:1.1.1 .   # 或由 compose 构建
docker run -d --name bq-probe --restart unless-stopped \
  --pid=host -v /tmp:/tmp \
  -e SERVER_URL=http://<平台IP>:8080 \
  -e AGENT_TOKEN=<与平台一致的令牌> \
  -e PROBE_NAME=<节点显示名，默认取 hostname> \
  bianque/probe:1.1.1
```

同机快速体验（平台本身用 compose 部署时）：`docker compose -f docker-compose.prod.yml --profile probe up -d probe`
——compose 内置 `probe` 服务已带 `pid=host` 与 `/tmp` 挂载，经网络别名 `server` 直连。

只有探针要发给目标主机时，用独立镜像包（约 210MB，仅 `bianque/probe:1.1.1`）：
`docker load -i bianque-probe-1.1.1.tar.gz`，运行命令同上；离线交付包内附
`probe/README-探针镜像.md` 图文说明。

## ③ 节点探针 · 裸机 systemd（已装 JDK 的 Linux 主机）

```bash
# 交付包 probe/ 布局安装（bq-agent.jar + arthas/ 完整发行目录随包分发）
sudo ./install.sh --server http://<平台IP>:8080 --token <令牌>
# 与目标 JVM 同用户运行（推荐，可靠 attach 且最小权限；每用户一个探针实例）
sudo ./install.sh --server http://<平台IP>:8080 --token <令牌> --user <应用用户>
# 可选: --jar /path/bq-agent.jar --arthas /path/发行目录或arthas-boot.jar --name 节点名
#       --install-dir /opt/bq-probe --java /opt/jdk21 | /opt/jdk21/bin/java
```

安装器行为：拷贝 `bq-agent.jar` 与 `arthas/` 发行目录到安装目录（发行目录自动 `a+rX`，
保证目标 JVM 用户可读）→ 写入 `bq-probe.service`（自动重启，`--user` 时写入运行账号）→ `enable --now`。
日志：`journalctl -u bq-probe -f`；卸载：`systemctl disable --now bq-probe && rm -rf <安装目录>`。

**attach 与用户（重要）**：agent jar 由目标 JVM 以其自身用户打开——跨用户 attach 失败的根因
不是"探针权限不够"，而是目标进程读不到探针侧文件：arthas-boot 缺省把发行包解压到运行者家目录
（root 时为 `/root`，0700），非 root 目标进程读不到，报 `Agent JAR not found`。两条出路：

1. **同用户运行**（推荐）：`--user <应用用户>`，可靠 attach 且最小权限；多个应用用户各装一个探针；
2. **发行目录对目标用户可读**：安装器/探针镜像已内置完整 arthas 发行目录（attach 显式
   `--arthas-home`，不再解压到运行者家目录），root 探针也可 attach 其它用户的应用，
   同时离线内网免运行时下载（arthas-boot 缺省首次 attach 需从外网下载约 17MB，内网会直接失败）。

**Windows 目标主机**：无需服务化，直接

```bat
java -jar bq-agent.jar --server http://平台IP:8080 --token <令牌> --arthas arthas-boot.jar
```

（可用任务计划/服务包装器常驻；探针所在目录需可写，用于保存节点身份 `bq-agent.id`。）

## 端口与网络要求

| 方向 | 端口/目标 | 用途 |
|---|---|---|
| 用户 → web | TCP 80（`WEB_PORT`） | 平台界面 |
| 用户/探针 → server | TCP 8080 | API + 探针注册/心跳/命令单（跨主机探针必须能出站访问平台 8080） |
| 探针 → 目标 JVM | 本机 attach + 本机回环端口（28600+） | 探针与目标进程同机通信，**不经过网络** |

目标主机**无需开放任何入站端口**——探针只做出站 HTTP。

## 探针工作原理与边界（务必阅读）

- 心跳 5s 上报本机 JVM 进程清单（attach API，异常回落 jps）；平台命令单下发，节点串行执行后回传
- **attach 与用户**：agent jar 由目标 JVM 以其自身用户打开，同用户或发行目录可读方可 attach（详见 ③ 节说明）；
  容器探针默认 root，配合内置发行目录可附加其它用户启动的宿主 JVM；非 root 目标进程也可在对应用户下另行运行探针
- **同一 JVM 只允许一个 arthas agent**：目标进程此前被手工 attach 过且未 stop 的，先停止旧 agent
- 探针空闲 30 分钟自动回收 arthas 会话；平台侧会话关闭时下发 stop，防增强残留
- `bianque.agent.token` 为空时探针接入开放（仅限内网演示）；生产必配且探针侧一致
- 完整安全边界见根 [README](../README.md)「红线」与「已知边界」章节
