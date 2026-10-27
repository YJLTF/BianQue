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

## 快速开始（V0.1.0，第一个可用版本）

```bash
# 1. 后端（拟真演示通道，无需真实 agent）
cd server
mvn -B package
java -jar bq-server/target/bq-server-0.1.0-SNAPSHOT.jar     # http://127.0.0.1:8080

# 2. 前端（开发模式，/api 与 /docs 代理到 8080）
cd web
npm install
npm run dev                                                  # http://localhost:5173
```

V0.1.0 可用能力：工作台总览、诊断台（表单/模板/命令执行、高危黑名单拦截、会话 30 分钟托管）、
审计中心（全操作留痕）、急诊取证（告警→现场报告）、巡检体检（虚拟线程并行巡检+健康分）、
档案扫描（批量 sc -d 漏洞应急）、文档中心（Arthas 4.3.5 官方 78 页离线托管+全文搜索）、
安全基线下发与 agent 安装向导。`bianque.execution.mode=local` 可切换本机 as.sh 真实执行。

## 开发环境要求（规划）

- 后端：JDK 21、Maven 3.9+；前端：Node 20+；管线/脚本：Python 3.12
- 本地编排：Docker + Docker Compose（MySQL 8 / Redis 7 / MinIO / Nginx）
- 诊断靶场：Docker 运行 `math-game.jar`（发行包自带演示程序，JDK 8/11/17/21 多版本）

## 红线（所有阶段生效）

默认绑定 `127.0.0.1`、强制认证、`disabledCommands` 黑名单、全操作审计、会话超时自动回收。发布门禁见产品设计方案 6.3。
