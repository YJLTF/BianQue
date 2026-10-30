# deploy（部署）· v1.1.2

扁鹊平台部署形态：

| 形态 | 适用 | 入口 |
|---|---|---|
| ⓪ 离线交付包部署 | 目标机无构建条件（内网常用） | `dist/bianque-images-runtime-latest.tar.gz` + `deploy/artifacts/` 产物 + 本目录 |
| ① 平台全容器一键部署 | 生产/演示服务器（只需 Docker） | [`docker-compose.prod.yml`](docker-compose.prod.yml) + [`.env.example`](.env.example) |
| ② 节点探针 · Docker | Linux 目标主机（推荐，与平台镜像同源） | [`Dockerfile.probe`](docker/Dockerfile.probe) |
| ③ 节点探针 · 裸机 systemd | 已装 JDK 11+ 的 Linux 主机 | [`probe/install.sh`](probe/install.sh) |

开发者在宿主机直跑后端/前端时，只需基础设施层 [`docker-compose.yml`](docker-compose.yml)（MySQL + Redis）。
使用指南（页面操作/工作流/FAQ）见 [`docs/USAGE.md`](../docs/USAGE.md)。

---

## ⓪ 离线交付包部署（无需构建）

交付包 = **运行环境镜像包**（`docker save` 导出，见 ⓪.1 ⑥）+ **代码产物目录** `deploy/artifacts/`（bq-server.jar / bq-agent.jar / web/）。镜像只是运行环境承载（tag `latest`），代码版本随 artifacts 走：

```bash
docker load -i bianque-images-runtime-latest.tar.gz   # bianque/server|web|probe:latest（纯运行环境）+ mysql:8.4 + redis:7.4
cd deploy
cp .env.example .env                          # 生产必配：AGENT_TOKEN、BIANQUE_ADMIN_PASSWORD（首次播种 admin 口令）
docker compose -f docker-compose.prod.yml up -d   # 镜像与产物就位，无需 --build
```

## ① 平台一键部署（全容器）

**架构**：`web`(Nginx 静态 + 反代) → `server`(Spring Boot) → `mysql` / `redis`；目标主机另装探针（见下节）回连注册。

```
浏览器 ──80──> bq-web(Nginx) ──/api /docs──> bq-server:8080 ──> mysql / redis
目标主机探针 bq-agent ──出站 HTTP(5s 心跳)──> http://<平台>:8080/api/agent/**
```

**前置**：Docker 20+ 与 Docker Compose v2。镜像为**纯运行环境承载**（v1.1.1 起，产物经 `deploy/artifacts/`
挂载，不在镜像里）：`--build` 部署需先按 ⓪.1 出包归位 artifacts；仅部署已有镜像（⓪ 交付包）则只需 Docker。

```bash
cd deploy
cp .env.example .env                # 修改 AGENT_TOKEN 等配置（compose 自动读取）
docker compose -f docker-compose.prod.yml up -d --build
```

**日常升级（镜像不动）**：替换 `deploy/artifacts/` 产物即可——
前端免重启（目录即时生效）；后端/探针 `docker compose -f docker-compose.prod.yml restart server probe`。
**jar 必须原子替换**（先拷临时名再 `mv -f` 覆盖，勿直接 `cp` 覆写）：运行中的 Spring Boot 持有 jar 文件句柄，
原地覆写会让未加载的类炸 `NoClassDefFoundError`（重启前的请求随机 500）。版本以产物与 `/api/health`
回显为准（build-info 注入，无硬编码）。改 compose 配置 / 环境变量 / 运行环境（JRE、arthas 版本）时才需 `up -d --build`。

## ⓪.1 构建发布（开发机出镜像）

宿主全量构建（清 target 规避 IDE 增量产物混入）→ 产物归位 artifacts → 环境镜像装载（仅首次）→ 导出离线包：

```bash
# ① 服务端 + 探针出包（清 target 全量编译；JDK 21 环境，本地镜像源下约 12s）
cd server && mvn -B -pl bq-server,bq-agent -am clean package -DskipTests && cd ..
# ② 前端 dist
cd web && npm ci && npm run build && cd ..
# ③ 产物归位（规范化命名，compose 按此挂载；artifacts 目录不入库）
#    jar 用「拷临时名 + mv -f」原子替换：目标机上运行中的服务端持有旧文件句柄，原地 cp 覆写会致其类加载炸裂
cp server/bq-server/target/bq-server-*.jar deploy/artifacts/.bq-server.jar.tmp && mv -f deploy/artifacts/.bq-server.jar.tmp deploy/artifacts/bq-server.jar
cp server/bq-agent/target/bq-agent.jar     deploy/artifacts/.bq-agent.jar.tmp && mv -f deploy/artifacts/.bq-agent.jar.tmp deploy/artifacts/bq-agent.jar
rm -rf deploy/artifacts/web && cp -r web/dist deploy/artifacts/web
# ④ 环境镜像装载（仅首次或环境变化时：JRE/nginx/arthas 版本升级；秒级，产物不进镜像）
docker build -f deploy/docker/Dockerfile.server -t bianque/server:latest .
docker build -f deploy/docker/Dockerfile.web    -t bianque/web:latest .
docker build -f deploy/docker/Dockerfile.probe  -t bianque/probe:latest .
# ⑤ 交付前复核探针字节码基线（major 55 = Java 11；防宿主 IDE 产物混入高版本字节码）
unzip -p deploy/artifacts/bq-agent.jar com/bianque/agent/AgentMain.class | od -A d -t x1 | head -1
# ⑥ 导出环境镜像离线包（三运行环境镜像 + mysql/redis；代码产物随 deploy/artifacts 目录另行分发）
docker save bianque/server:latest bianque/web:latest bianque/probe:latest mysql:8.4 redis:7.4 \
  | gzip > dist/bianque-images-runtime-latest.tar.gz
```

> 历史口径（v1.1.0 及以前）为容器内多阶段构建：不依赖宿主构建环境，但冷构建需在容器内联网爬全部依赖
> （实测 20+ 分钟）；已按「快速发布运行」定位改为宿主装载式。需要容器内全量复现构建时，检出对应
> 历史 Dockerfile 即可。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `BQ_VERSION` | latest | 运行环境镜像 tag（镜像不随代码版本变化；代码版本以 `deploy/artifacts` 产物与 `/api/health` 回显为准） |
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
curl http://<主机>/api/health                 # {"app":"bianque-server","version":"1.1.2","status":"UP",...}
# 浏览器打开 http://<主机>/ → 节点管理页
# 登录：演示模式 admin/admin123；生产模式用 BIANQUE_ADMIN_PASSWORD 设置的口令
```

**说明**：

- 容器化 server **自动关闭本机纳管通道**（`bianque.execution.local-enabled=false`）——容器内看不到宿主 JVM，
  真实诊断一律由目标主机探针回连提供；拟真靶场、审计、巡检、AI 问诊等全功能可用
- **`GET /api/agent/download` 开箱即用**：下载路径指向挂载目录（`deploy/artifacts/bq-agent.jar` →
  `/app/artifacts/`），替换产物后接口即吐新包，节点接入指引的下载步骤无需手工放置
- 数据落 MySQL volume（`mysql-data`），重启/升级不丢；server 日志与临时文件在 `server-data` volume
- 升级：原子替换 `deploy/artifacts/` 产物（mv，勿原地 cp，见「日常升级」）→
  `docker compose -f docker-compose.prod.yml restart server probe`
  （前端免重启；DDL 自动增量，向后兼容）。仅当运行环境（JRE/nginx/arthas）变化时才重建镜像
- 备份：`docker exec bq-mysql sh -c "mysqldump -ubianque -p\$MYSQL_PASSWORD bianque" > backup.sql`
- TLS：镜像内不做 TLS，生产建议外层 Nginx/网关统一终结 HTTPS 后反代到 `WEB_PORT`

## ② 节点探针 · Docker（Linux 主机推荐）

探针镜像为纯运行环境（JDK 17 + 完整 arthas 发行目录 `/probe/arthas/`，attach 显式 --arthas-home：
离线免下载、对目标 JVM 用户可读，root 探针可 attach 其它用户的应用）；探针包 `bq-agent.jar` 经
volumes 挂载（升级 = 替换宿主产物 + 重启容器）。剖析宿主 JVM 时**必须** `--pid=host` 并挂载 `/tmp`
（JVM attach 依赖宿主 `/tmp` 下的 hsperfdata/attach socket），探针以 root 运行可附加 root 启动的 JVM。
**勿用此形态 attach 容器内 JVM**：容器目标的 hsperfdata pid 属容器命名空间（容器内 pid 1），
宿主命名空间按其 attach 等于向宿主 init 发信号——容器目标请让探针与目标共享命名空间
（compose `--profile probe` 演示即 `pid: service:server` + 共享卷，见 docker-compose.prod.yml 注释）：

```bash
docker build -f deploy/docker/Dockerfile.probe -t bianque/probe:latest .   # 或由 compose 构建（仅首次）
mkdir -p /opt/bianque/artifacts && cp bq-agent.jar /opt/bianque/artifacts/   # 产物随包分发或从平台 /api/agent/download 获取
docker run -d --name bq-probe --restart unless-stopped \
  --pid=host -v /tmp:/tmp \
  -v /opt/bianque/artifacts:/probe/artifacts:ro \
  -e SERVER_URL=http://<平台IP>:8080 \
  -e AGENT_TOKEN=<与平台一致的令牌> \
  -e PROBE_NAME=<节点显示名，默认取 hostname> \
  bianque/probe:latest
```

同机快速体验（平台本身用 compose 部署时）：`docker compose --profile probe -f docker-compose.prod.yml up -d probe`
——compose 内置 `probe` 服务与 `server` 共享 pid/网络命名空间和 exec tmpdir 卷（`bq-tmp`），
注册即可见本栈 server JVM，诊断台「性能剖析」可直接对其采样出火焰图（无需任何宿主 JVM）。

只有探针要发给目标主机时，用独立镜像包（约 210MB，仅 `bianque/probe:latest` 运行环境）：
`docker load -i bianque-probe-runtime-latest.tar.gz`，运行命令同上（另需带 `bq-agent.jar` 产物目录）；
离线交付包内附 `probe/README-探针镜像.md` 图文说明。

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
- **跨命名空间 attach 禁止**：attach API 枚举依赖目标 tmpdir 下的 hsperfdata，其文件名 pid 属目标
  自己的命名空间；探针按**自己的**命名空间解释该 pid 去 attach——目标在其它 pid 命名空间（容器）时
  会 attach 到错误进程（命中 pid 1 即向宿 init 发 SIGQUIT，实测可带崩整台 Docker VM）。
  容器目标必须与探针共享 pid 命名空间（compose 演示形态）或直接裸机探针
- **目标 tmpdir 必须可执行**：async-profiler 把采样 .so 解压到目标 `java.io.tmpdir` 再 dlopen，
  /tmp 挂载为 noexec tmpfs 时报 `failed to map segment from shared object`——compose 演示以
  exec 卷 `bq-tmp` 规避；宿主机 /tmp 为 noexec 的环境需调整挂载或目标 JVM 的 `-Djava.io.tmpdir`
- 探针空闲 30 分钟自动回收 arthas 会话；平台侧会话关闭时下发 stop，防增强残留
- `bianque.agent.token` 为空时探针接入开放（仅限内网演示）；生产必配且探针侧一致
- 完整安全边界见根 [README](../README.md)「红线」与「已知边界」章节
