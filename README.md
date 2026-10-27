# 扁鹊 · BianQue — 基于 Arthas 4.3.5 的线上诊断平台

> 望闻问切，线上无障。把"SSH → arthas-boot → 敲命令"变成"浏览器选应用 → 选场景 → 看结论"，并用安全壳管住 Arthas 的进程内任意代码执行能力。

## 项目文档地图

| 阶段 | 文档 | 位置 |
|---|---|---|
| 调研 | Arthas 4.3.5 运维产品调研评估报告（产品机会矩阵、品牌体系、安全红线） | [docs/research/Arthas-4.3.5-运维产品调研评估报告.md](docs/research/Arthas-4.3.5-运维产品调研评估报告.md) |
| 设计 | 产品设计方案 V1.0（架构 / 易用性 / 文档集成 / 安全可靠性 / 路线图） | [design/BianQue-产品设计方案-v1.0.md](design/BianQue-产品设计方案-v1.0.md) |
| 设计 | 可交互界面原型（8 类界面，含文档中心） | [design/BianQue-界面原型-离线版.html](design/BianQue-界面原型-离线版.html) |
| 选型 | 技术选型 V1.0（前端 / 后端 / 存储 / 纳管 / MCP / 部署） | [plan/BianQue-技术选型-v1.0.md](plan/BianQue-技术选型-v1.0.md) |
| 计划 | 开发计划 V1.0（S0–S5 里程碑 / 人力 / 验收门禁 / 风险） | [plan/BianQue-开发计划-v1.0.md](plan/BianQue-开发计划-v1.0.md) |

## 目录结构

```
bianque/
├── README.md                    # 本文件：导览
├── materials/                   # 原始发行物（只读物料）
│   ├── arthas-packaging-4.3.5-bin.zip     # Arthas 官方二进制包（探针/引擎来源）
│   └── arthas-packaging-4.3.5-doc.zip     # Arthas 官方文档包（文档中心来源）
├── docs/
│   ├── research/
│   │   ├── Arthas-4.3.5-运维产品调研评估报告.md
│   │   └── arthas-inspect/      # 发行包解包取证材料（调研实证，报告引用）
│   └── arthas-docs/
│       └── 4.3.5/               # 文档包解包产物（文档中心管线输入，版本锚定）
├── design/                      # 产品设计交付物
│   ├── BianQue-产品设计方案-v1.0.md
│   ├── BianQue-界面原型-离线版.html
│   └── arthas-docs-manifest.json          # 78 页文档清单（slug/分类/风险等级）
├── plan/                        # 技术选型与开发计划
├── server/    （S0 已建骨架）    # 后端：Spring Boot 3 多模块（bq-core / bq-server）
├── web/       （S0 已建骨架）    # 前端：Vue 3 + TS + Vite + Element Plus
├── docs-center/（阶段 2 创建）   # 文档中心构建管线（Python）
├── deploy/                      # Compose 编排、离线部署包、agent 安装脚本
└── scripts/                     # 开发脚本 + 诊断靶场（math-game 故障注入）
```

## 文件迁移映射（2026-10-27 重构）

| 原位置 | 新位置 |
|---|---|
| `Arthas-4.3.5-运维产品调研评估报告.md` | `docs/research/` |
| `arthas-inspect/` | `docs/research/arthas-inspect/` |
| `arthas-packaging-4.3.5-bin.zip` | `materials/` |
| `arthas-packaging-4.3.5-doc.zip` | `materials/` |
| `docs-unpack/` | `docs/arthas-docs/4.3.5/` |

## 快速开始（V0.1.x · S1 公共底座已就位）

```bash
# 0. 基础设施（推荐，S3 起默认路径）：MySQL 8.4 + Redis 7.4
docker compose -f deploy/docker-compose.yml up -d mysql redis

# 1. 后端 —— 必须在仓库根目录启动（文档目录按相对路径解析）
cd server && mvn -B package && cd ..
#    生产/联调模式：数据落 MySQL、登录态存 Redis（重启不掉登录态、纳管关系保留）
java -jar server/bq-server/target/bq-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
#    或轻量演示模式（H2 文件库 + 内存登录态，无需 Docker）：
#    java -jar server/bq-server/target/bq-server-0.1.0-SNAPSHOT.jar

# 2. 前端（开发模式，/api 与 /docs 代理到 8080）
cd web && npm install && npm run dev                             # http://localhost:5173
```

登录账号（首次启动自动种子，RBAC 五表：users/roles/permissions/user_roles/role_permissions）：

| 账号 | 口令 | 角色 | 权限 |
|------|------|------|------|
| admin | admin123 | ADMIN（管理员） | 全量（含用户管理） |
| ops | ops123 | OPERATOR（运维） | 只读 + 执行诊断/巡检/取证/扫描 |
| viewer | viewer123 | VIEWER（只读） | 仅浏览，写操作返回 403 |

S1 可用能力（在 V0.1.0 八大功能流之上新增）：

- **登录认证与 RBAC**：登录页签发 token（默认 8h 滑动过期），/api/** 全量鉴权（登录与探活除外），
  写操作需 `diag:execute`，用户管理仅 ADMIN；前端路由守卫 + axios 自动携带 token + 401 自动跳登录。
- **审计哈希链防篡改**：每条审计 `hash = sha256(记录内容 + prevHash)`，首条创世；审计中心一键
  "校验哈希链"，任何记录被篡改即断链可检（`GET /api/audit/verify`）。
- **持久化（H2 → MySQL 可切换）**：审计、巡检运行、急诊报告落库（默认 H2 文件库 MODE=MySQL，
  数据在 `./data/`）；生产切换 `--spring.profiles.active=mysql`（见 deploy/docker-compose.yml）。
  巡检结果重启不丢，`/api/checkup/history` 可回看最近 20 次。
- **真实操作人审计**：所有诊断动作记录登录账号（不再是演示账号）。

**S4 常驻会话与告警接入**：
- 常驻会话：纳管实例首次执行时 attach-only 建立常驻 agent，热命令（version/jvm/memory/thread）
  直连 agent HTTP 端口毫秒级返回（实测 376~510ms，对比一次性通道 1.2~1.4s）；其余命令自动回落
  一次性 boot 通道；会话回收/超时对真实实例执行真实 stop（卸载增强防残留）。
- 告警接入（仅预留通用接口，不绑定企微/钉钉）：入站 `POST /api/alerts/ingest`（X-Alert-Token 认证，
  `bianque.alerts.ingest-token` 配置后开放），事件落 MySQL `alert_event` 表；出站 `AlertNotifier` SPI
  （实现 Bean 即插即用）+ 内置通用 Webhook（`bianque.alerts.webhook-url`），新告警/取证完成时推送 JSON。

**S3 真库真缓存**：`--spring.profiles.active=mysql` 时数据落 MySQL 8.4（docker）、登录态存 Redis 7.4（滑动过期 8h）。
实测收益：后端重启后 token 直接可用、已纳管实例自动恢复（免重新 attach）、审计哈希链跨重启完整校验。

**S2 真实诊断闭环（免 telnet）**：诊断台"本机进程纳管"卡片可扫描本机 JVM（jps）并一键纳管，
纳管实例自动路由到 arthas-boot.jar 纯 Java 通道（`java -jar arthas-boot.jar --select <主类> -c "<命令>"`，
不依赖系统 telnet，内网禁 telnet 环境可用）；靶场实例继续走拟真通道。启动靶场演示进程：
`java -jar docs/research/arthas-inspect/math-game.jar`。开关：`bianque.execution.local-enabled`（生产建议 false）。

V0.1.0 基础能力保留：工作台总览、诊断台（模板/命令执行、高危黑名单拦截、会话 30 分钟托管）、
急诊取证、巡检体检（虚拟线程并行+健康分）、档案扫描（批量 sc -d）、
文档中心（Arthas 4.3.5 官方 78 页离线托管+全文搜索）、安全基线与 agent 安装向导。
`bianque.execution.mode=local` 可切换本机 as.sh 真实执行。

## 开发环境要求（规划）

- 后端：JDK 21、Maven 3.9+；前端：Node 20+；管线/脚本：Python 3.12
- 本地编排：Docker + Docker Compose（MySQL 8 / Redis 7 / MinIO / Nginx）
- 诊断靶场：Docker 运行 `math-game.jar`（发行包自带演示程序，JDK 8/11/17/21 多版本）

## 红线（所有阶段生效）

默认绑定 `127.0.0.1`、强制认证、`disabledCommands` 黑名单、全操作审计、会话超时自动回收。发布门禁见产品设计方案 6.3。
