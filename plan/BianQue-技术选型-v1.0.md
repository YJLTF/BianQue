# 扁鹊 · BianQue 技术选型 V1.0

| 项 | 内容 |
|---|---|
| 依据 | 《扁鹊-产品设计方案-v1.0》（架构/功能/安全约束）、《Arthas-4.3.5 调研评估报告》（能力边界实测） |
| 原则 | ① 复用 Arthas 官方组件与协议，不重造诊断能力；② 单体起步、模块化边界、可拆微服务；③ 全链路内网可离线部署；④ 选型服从安全红线 |
| 日期 | 2026-10-27 |

---

## 1. 总体工程形态

- **Monorepo**：`server/`（Java 多模块）+ `web/`（前端 SPA）+ `docs-center/`（文档管线）+ `deploy/` + `scripts/`，单仓单流水线，按目录权限协作。
- **B/S 架构**，平台服务单体起步（按产品设计方案第 3 章分层），模块边界 = 未来拆分边界。
- **语言栈统一 Java 21**：与 Arthas / arthas-tunnel-server 同生态，官方协议类可直接复用。

## 2. 前端

| 项 | 选型 | 理由 / 备注 |
|---|---|---|
| 框架 | **Vue 3.4 + TypeScript + Vite 5**（`<script setup>` 组合式 API） | 团队既有技术栈为 Vue；Vite 冷启动快；单文件组件利于诊断台等复杂页面演进 |
| 组件库 | **Element Plus** | Vue 3 生态最成熟的企业级组件库：表格 / 树 / 表单 / 抽屉 / 步骤条密度高，契合诊断台与审计中心（备选 Arco Design Vue） |
| 状态与请求 | **Pinia + VueUse（useIntervalFn 等）+ Axios 封装** | 服务端状态（实例/任务轮询）用组合式 composable 统一封装，本地 UI 状态 Pinia |
| 图表 | **ECharts 5（vue-echarts）**（趋势/仪表/拓扑）+ **自研 SVG 火焰图组件**（D3 hierarchy 布局） | 火焰图需交互缩放与 diff，无现成组件满足 |
| 终端 | **xterm.js + WebSocket** | 会诊 Web Console 直连官方 console（P3 阶段） |
| 表达式编辑 | **Monaco Editor**（OGNL/命令编辑，高危红框主题） | 语法高亮 + 历史模板补全 |
| 测试 | Vitest + Vue Test Utils + Playwright | Playwright 兼做界面验收回归 |

## 3. 后端（server/，Maven 多模块）

| 模块 | 职责 |
|---|---|
| `bq-server` | Web API、认证授权、应用/实例台账、任务编排、审计中心 |
| `bq-core` | **执行引擎**（batch/command 双通道封装、限频、超时、重试、结果结构化解析）、告警取证流水线、巡检调度 |
| `bq-tunnel` | 内嵌官方 **arthas-tunnel-server**，agent 注册/心跳/会话路由（协议零改动） |
| `bq-mcp-gateway` | 问诊 MCP 聚合网关（S3 阶段） |
| `bq-docs` | 文档中心静态托管 + 搜索索引服务 |

**框架与关键库**

- **Spring Boot 3.3 + JDK 21**（虚拟线程用于并发批量扫描/巡检 IO）。
- 执行通道选择：**批量任务走 `as.sh --batch-file`**（稳定、可回放、天然限流），**实时会话走 HTTP API 8563**（低延迟）；两通道统一抽象为 `DiagExecutor` 接口。
- 弹性：**Resilience4j**（RateLimiter 按主机限频、Bulkhead 并发隔离、Retry 仅只读命令、CircuitBreaker 主机熔断）。
- 调度：Spring Scheduling + DB 任务表（乐观锁分片），规模化后换 XXL-Job。
- 备选方案 Go：部署更轻，但需重写 tunnel 协议、MCP 生态弱，**不选**。

## 4. MCP / 问诊（P2）

- **个人版**：零开发——平台提供接入指引页，用户在 AI 客户端配置发行包内置端点 `http://127.0.0.1:8563/mcp`（STREAMABLE HTTP，29 工具，Bearer Token）。
- **平台版**：**MCP Java 官方 SDK** 实现聚合网关（协议版本锚定 2025-06-18，适配层隔离演进）：`list_apps` / `pick_process` / 按租户转发诊断工具；强制只读白名单、逐步审计、会话限流、高危动作转人工确认工单。
- **LLM 接入**：OpenAI 兼容适配层，默认对接内网私有化模型（Qwen / DeepSeek / GLM 任一），可插拔；**Playbook 用 YAML 声明式 SOP**（CPU 高 / OOM / 慢接口三模板起步），约束 AI 按采集路径执行而非自由发挥。

## 5. 存储与搜索

| 用途 | 选型 | 备注 |
|---|---|---|
| 业务主库 | **MySQL 8** | 审计表按月分区 + 追加写 + 前哈希链字段（防篡改） |
| 对象存储 | **MinIO**（S3 协议） | heapdump / 火焰图 / 报告归档；服务端加密 + 生命周期 7 天强制清理 |
| 缓存/会话 | **Redis 7** | 会话状态、分布式锁、限频计数、会话倒计时兜底 |
| 站内搜索 | 起步 **MySQL fulltext + 构建期 MiniSearch 前端索引**（文档中心） | 规模化后换 Elasticsearch，搜索接口先抽象隔离 |
| 报文/日志 | Logback JSON + 可选 Loki | 平台自身接入 Prometheus + Grafana（吃自己狗粮） |

## 6. 认证与安全组件

- **Spring Security + OAuth2/OIDC**：对接内网 Keycloak / 公司 SSO，MFA 由 IdP 承担；会话 30 分钟。
- **RBAC 自研**（应用 × 命令族 × 角色，五表模型，见设计方案第 7 章）；高危操作接审批工单流（起步用平台内置工单，不引外部 BPM）。
- 密钥/凭据：Vault（或 Compose secrets 注入），agent 密码不落库明文。
- 落盘加密：AES-256-GCM（heapdump、导出报告），密钥轮换接口预留。
- 工程安全门禁：gitleaks（密钥扫描）、OWASP dependency-check、Trivy（镜像）。

## 7. 文档中心管线（docs-center/）

```
materials/arthas-packaging-4.3.5-doc.zip
  → 解包至 docs/arthas-docs/{version}/          （已交付：4.3.5）
  → 生成/校验 design/arthas-docs-manifest.json  （已交付：78 页 + 风险等级）
  → 抽取正文 → MiniSearch 构建期索引（zh+en）
  → 产物输出 deploy/docs-site/{version}/，由 bq-docs/nginx 托管 /docs/{version}/
```

管线用 **Python 3.12**（解析/抽取生态成熟，脚本已验证）；CI 中文档 zip 更新即自动重建；版本并存，默认展示与实例 agent 版本一致的文档。

## 8. 部署与交付（deploy/）

- **起步：Docker Compose 单机编排**——`mysql / redis / minio / bq-server / bq-tunnel / nginx(web 静态+反代)`，一份 `.env` 管配置。
- **离线部署包**（生产区无外网刚需）：镜像 `docker save` tar + agent 安装器 + 文档站 + 一键安装脚本；升级走版本化离线包。
- **规模化后**：Helm Chart / K8s（bq-server 无状态水平扩展，任务表分片保证不重不漏）。
- CI/CD：内网 GitLab CI（或 GitHub Actions），流水线 = 构建 → 单测 → Testcontainers 集成测试 → 镜像 → 部署靶场冒烟。

## 9. 测试与靶场（scripts/）

- 单测：JUnit 5 / Vitest；集成：**Testcontainers** 拉起 MySQL/Redis/MinIO。
- **诊断靶场**：发行包自带 `math-game.jar` 容器化（JDK 8/11/17/21 多标签），配套故障注入脚本（CPU 打满 / 内存缓慢泄漏 / 线程死锁 / 连接池耗尽），供全流程真实 attach 验收与演示——零业务数据风险。
- 性能基线：attach 停顿（目标 <2s）、增强开销（watch 长挂 <3% CPU）、平台并发扫描吞吐；发布前出基线报告。
- JDK 兼容矩阵：靶场多版本跑通 P1/P5/P6 全部采集模板，出矩阵实测表。

## 10. 选型决策记录（ADR 摘要）

| # | 决策 | 理由摘要 |
|---|---|---|
| ADR-1 | 平台栈选 Java 而非 Go | 与 Arthas/tunnel 同生态，官方组件直接内嵌；MCP SDK 成熟 |
| ADR-2 | 执行双通道（batch-file + HTTP API） | 批量重稳定可回放，会话重低延迟，统一抽象隔离 |
| ADR-3 | 单体起步模块化 | 4 人团队规模，先降运维成本；模块边界即未来拆分线 |
| ADR-4 | 搜索先 MySQL/MiniSearch 后 ES | 避免过早引入重组件；接口隔离保留替换权 |
| ADR-5 | MCP 协议版本锚定 + 适配层 | 4.3.5 内置实现为 2025-06-18；防协议演进冲垮网关 |
| ADR-6 | 靶场用 math-game 容器化 | 官方演示程序零数据风险，故障可控可复现 |
| ADR-7 | 前端选型由 React 改为 **Vue 3 + Element Plus**（2026-10-27，团队决策） | 团队既有栈为 Vue；Element Plus 企业组件覆盖度相当，后端 API 契约不变 |
