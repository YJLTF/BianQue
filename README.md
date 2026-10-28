# 扁鹊 · BianQue v1.0.0 — 基于 Arthas 4.3.5 的线上诊断平台

> 望闻问切，线上无障。把"SSH → arthas-boot → 敲命令"变成"浏览器选应用 → 选场景 → 看结论"，
> 并用安全壳管住 Arthas 进程内任意代码执行能力。

**版本**：v1.0.0（2026-10）· 引擎 Arthas 4.3.5 · 运行时 JRE 21 · 目标 JVM JDK 11+

## 核心能力

- **真实诊断**：节点探针（纯 JDK 零依赖，出站 HTTP 心跳）纳管目标主机 JVM，命令单下发 →
  arthas attach 执行 → 结果回传；本机纳管通道（免 telnet）覆盖单机场景；高危命令黑名单、
  增强命令限次限频、会话 30 分钟自动回收（reset+stop 防增强残留）
- **AI 问诊**：任何 OpenAI Chat Completions 兼容端点（含推理模型思考强度）；自动检索文档中心
  78 页官方文档作知识库（Top-4 命中注入，引用可溯源）；密钥集中管理、每次对话审计留痕
- **急诊取证**：外部告警 Webhook 接入（级别归一化）→ 一键生成现场报告
- **巡检体检**：虚拟线程并行体检机队，健康分 + 扣分项，单实例失败弹性降级
- **资产档案**：应用/实例/进程台账，批量 `sc -d` 扫描
- **文档中心**：官方 78 页离线托管 + 全文搜索 + 深链
- **安全底座**：登录认证 + RBAC 三角色、审计哈希链防篡改、探针/告警双令牌接入、密钥不出平台

## 文档地图

| 文档 | 内容 | 位置 |
|---|---|---|
| **部署指南** | 一键部署 / 离线交付包 / 节点探针（Docker・systemd・Windows） | [deploy/README.md](deploy/README.md) |
| **使用指南** | 角色权限、十页导览、典型工作流、AI/MCP 接入、FAQ | [docs/USAGE.md](docs/USAGE.md) |
| 变更记录 | S0→S8 迭代与 v1.0.0 发布记录（每项含实测数据） | [CHANGELOG.md](CHANGELOG.md) |
| 验收清单 | S1–S7 【自动】/【手动】逐项核对 | [ACCEPTANCE.md](ACCEPTANCE.md) |
| 产品设计 | 产品方案 / 界面原型 / 技术选型 / 开发计划 | [design/](design/)、[plan/](plan/) |
| 调研报告 | Arthas 4.3.5 运维产品调研评估 | [docs/research/](docs/research/) |

## 目录结构

```
bianque/
├── README.md                    # 本文件：导览（能力 / 快速开始 / 红线与边界）
├── CHANGELOG.md                 # 迭代与发布记录
├── ACCEPTANCE.md                # 验收清单
├── materials/                   # 原始发行物（只读物料：Arthas bin/doc 包）
├── docs/
│   ├── USAGE.md                 # 使用指南
│   ├── research/                # 调研报告 + 发行包解包取证 + arthas-boot.jar/math-game.jar
│   └── arthas-docs/4.3.5/       # 文档中心源（78 页，随 server 镜像分发）
├── design/ plan/                # 设计与计划交付物（含 78 页文档清单 manifest）
├── server/                      # 后端 Spring Boot 3 多模块
│   ├── bq-core/                 #   命令白名单 / as.sh 命令行构建
│   ├── bq-server/               #   平台服务（API/审计/RBAC/巡检/AI/文档中心）
│   └── bq-agent/                #   节点探针（纯 JDK 11+ 零依赖，finalName=bq-agent.jar）
├── web/                         # 前端 Vue 3 + TS + Vite + Element Plus
├── deploy/                      # 部署：compose（prod/dev）、三份 Dockerfile、探针安装器、.env.example
│   └── probe/install.sh         #   裸机 systemd 安装器
└── scripts/                     # 开发脚本：start-all.bat / smoke.sh / dev-llm-mock.py
```

> 历史说明：S0 时代的文档构建管线（docs-center/ → deploy/docs-site/）已随 v1.0.0 移除，
> 全文检索由服务端 `DocsService` 运行时构建，无需预生成索引。

## 快速开始

**① 全容器一键部署（生产/演示服务器，只需 Docker）**：

```bash
cd deploy
cp .env.example .env            # 按需修改令牌/密码/LLM（生产必改 AGENT_TOKEN）
docker compose -f docker-compose.prod.yml up -d --build
# 浏览器打开 http://<主机>/ ；首次构建 5~15 分钟，详见 deploy/README.md
```

**② 离线部署（已有交付包，目标机无需构建）**：

```bash
docker load -i bianque-images-1.0.0.tar.gz   # 含 server/web/probe/mysql/redis 五镜像
cd deploy && cp .env.example .env && docker compose -f docker-compose.prod.yml up -d
```

**③ 开发模式（宿主机直跑）**：

```bash
# 一键全栈（Windows）：scripts\start-all.bat
# 或分步：
docker compose -f deploy/docker-compose.yml up -d mysql redis     # 基础设施
cd server && mvn -B package && cd ..
java -jar server/bq-server/target/bq-server-1.0.0.jar --spring.profiles.active=mysql
java -jar docs/research/arthas-inspect/math-game.jar              # 演示靶场进程
cp server/bq-agent/target/bq-agent.jar agents/                    # 本机演示节点（可选）
cd web && npm install && npm run dev                              # http://localhost:5173
```

**演示模式与生产模式**（`BIANQUE_DEMO_ENABLED`，prod compose 默认 `false`）：

- **演示模式 true**：首次启动种演示账号 + 模拟靶场实例/种子告警/拟真通道，开箱即可演练；
- **生产模式 false**：不建靶场、不种演示账号，实例只来自真实探针/本机纳管；
  admin 账号由 `BIANQUE_ADMIN_PASSWORD` 引导（仅 admin 不存在时生效一次，必须设置否则拒绝启动）；
  历史演示账号（ops/viewer）可在「管理设置 → 用户与角色」删除。

| 场景 | 演示模式（true） | 生产模式（false） |
|------|------|------|
| 账号 | admin/admin123、ops/ops123、viewer/viewer123 | 仅 admin（口令来自 BIANQUE_ADMIN_PASSWORD） |
| 实例/告警 | 模拟靶场 + 种子告警 | 仅真实探针上报 |

> 演示口令仅限内网演示。登录后可在侧边栏点击显示名改名；管理员在「管理设置」增删用户、改显示名。

## 版本升级与数据

- 数据落 MySQL volume（审计/巡检/取证/用户/LLM 配置），升级镜像不丢；DDL 自动增量
- 升级：改 `.env` 的 `BQ_VERSION` → `docker compose -f docker-compose.prod.yml up -d`（重新 build 同理）
- 备份：`docker exec bq-mysql mysqldump -ubianque -p$BIANQUE_DB_PASSWORD bianque > backup.sql`
- 部署校验：`curl http://<主机>/api/health` 返回 `{"app":"bianque-server","version":"1.0.0","status":"UP"}`

## 红线（所有阶段生效）

默认绑定 `127.0.0.1`、强制认证、`disabledCommands` 黑名单、全操作审计、会话超时自动回收。
发布门禁见产品设计方案 6.3；仓库与镜像不含任何真实密钥，默认口令仅限内网演示。

## 已知边界

- **同一目标进程只应归属一个通道**：arthas 每 JVM 仅一个 agent——本机纳管与节点探针混用时
  后 attach 的端口不生效。生产建议：有探针覆盖的主机不再启用本机纳管（容器化部署自动关闭）
- 节点探针为串行 worker（同一节点同时执行一条命令）；多节点天然并行
- 探针 Docker 形态仅 Linux 主机可 attach 宿主 JVM（`--pid=host -v /tmp:/tmp` 且与目标 JVM 同用户）；
  Windows 目标主机用裸机 `java -jar bq-agent.jar` 方式
- `AGENT_TOKEN` 为空时节点接入开放，**仅限演示模式**；生产模式（`BIANQUE_DEMO_ENABLED=false`）未配置令牌将拒绝启动；
  生产必配且与告警 `INGEST_TOKEN` 区分
- 口令 BCrypt 存储；历史 sha256 行首次登录成功后自动透明升级（老库无需迁移）
- AI 问诊文档知识库为检索注入（Top-4 命中片段），非向量库全量语义检索；冷门表述可能零命中
