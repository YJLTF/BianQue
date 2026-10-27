# 扁鹊 BianQue · 变更记录（CHANGELOG）

按迭代追加；每个迭代的验收细目见 [ACCEPTANCE.md](ACCEPTANCE.md)，部署操作见 [deploy/README.md](deploy/README.md)。

## S8 · 一键部署镜像 + 节点探针服务

- 三个镜像（多阶段构建，`docker compose -f deploy/docker-compose.prod.yml up -d --build` 一键起全栈）：
  `bianque/web`（Vite 构建 → Nginx 托管 + /api /docs 反代，76MB）、`bianque/server`（Spring Boot + 离线文档内嵌，597MB）、
  `bianque/probe`（bq-agent + arthas-boot，JDK 17）
- 新增 `docker` profile：compose 网络内连 `mysql`/`redis` 服务名；容器化 server 自动关闭本机纳管通道
  （容器内无宿主 JVM，真实诊断由探针回连提供）
- 节点探针两种安装形态：Docker（`--pid=host -v /tmp:/tmp`，attach 宿主 JVM）与裸机 systemd
  （`deploy/probe/install.sh` 一行安装/开机自启/自动重启）；Windows 主机直接 `java -jar` 运行
- 部署配套：探针接入/告警/LLM 密钥全部环境变量化；数据卷持久化（升级不丢数据）；
  移除开发 compose 中无引用的 minio 服务
- 实测：全栈起服 6.3s，Nginx 反代 health/登录/机队/文档目录（262KB）全通；探针容器经 compose
  网络注册成功心跳正常

## S7 · AI 问诊接入 LLM（0aec59c）

- 平台侧 LLM 接入（OpenAI Chat Completions 兼容）：配置落库 `llm_config` 在线编辑免重启，
  api-key 只回传掩码，支持 `BIANQUE_LLM_API_KEY` 环境变量预置
- 参数调节：model / temperature / top_p / max_tokens / 超时 / **思考强度 reasoning_effort**
  （推理模式自动省略 temperature、top_p 并以 `max_completion_tokens` 承载截断）
- 系统提示词页内可编辑（默认 JVM 诊断人设，只读白名单红线）；多轮对话 + 附加诊断上下文
- 权限：配置读写/连通测试仅 admin:user，chat 需 diag:execute；每次调用写审计哈希链
- 集成测试 6 项（JDK HttpServer 假 OpenAI 端点逐字段断言）；`scripts/dev-llm-mock.py` 内网演示假端点
- 设计边界：LLM 收敛平台侧（密钥集中+全审计），节点 Agent 保持零依赖、不持有密钥

## S6 · 平台版 Agent + UI 现代化（22af693）

- **平台版 Agent**（`server/bq-agent`，纯 JDK 11+ 零依赖）：纯出站 HTTP 心跳（注册/领单/回传，
  X-Agent-Token 认证），目标主机无需入站端口；心跳上报 JVM 进程自动转机队实例
- 命令单机制：MySQL 落库、原子领单、单节点串行、超时判死；节点按 PID attach-only
  （免同名多进程歧义）+ 子进程看门狗强杀（根治交互提示挂起）；空闲 30 分钟自动 stop
- 实测：冷 attach 2.8s / 热命令 265ms / 会话回收 40ms（靶进程 arthas 卸载验证）
- 服务端本机通道同步加固（selectTarget 改 PID、attach 看门狗、一次性通道 stdin 接空设备）
- 巡检弹性化：单实例失败/超时降级扣分项，不再拖垮整轮
- **UI 现代化**：全局设计系统（`web/src/styles/main.css`，靛青/朱砂/苍绿令牌 + Element Plus 主题覆盖）、
  墨蓝渐变侧栏 + 脉搏波 logo + 图标导航、登录页极光渐变、深色终端输出面板；新增「节点管理」页

## S5 · 验收工具链 + 审计并发修复（4cc1db1）

- 修复审计并发重号 bug（事务提交落在锁临界区外 → 自代理模式包住整个事务）+ 并发回归测试
- 通道可见性：执行结果标注 真实/拟真 徽标
- `scripts/start-all.bat` 一键启动、`scripts/smoke.sh` 冒烟（10+ 项断言）、`ACCEPTANCE.md` 验收清单

## S4 · 常驻会话 + 告警接入预留（17e372f）

- 常驻会话：attach-only 一次建连，热命令（version/jvm/memory/thread）HTTP 直连毫秒级；会话回收/超时真实 stop 防增强残留
- 告警接入（仅通用接口，不绑定企微/钉钉）：入站 `POST /api/alerts/ingest`（X-Alert-Token）；
  出站 `AlertNotifier` SPI + 通用 Webhook；告警事件落库

## S3 · 真库真缓存（77388c0）

- `--spring.profiles.active=mysql`：MySQL 8.4（Docker）持久化 + Redis 7.4 登录态（8h 滑动过期）
- 实测收益：后端重启 token 直接可用、纳管实例自动恢复、哈希链跨重启校验

## S2 · 真实诊断闭环（ecd0648）

- 诊断台本机 JVM 扫描（jps）→ 一键纳管 → arthas-boot.jar 纯 Java 通道真实执行（**免 telnet**，内网可用）
- 拟真靶场与真实通道按实例路由，高危黑名单全通道生效

## S1 · 公共底座（7ea739b）

- 登录认证 + RBAC 五表（admin/ops/viewer 演示账号）、/api/** 全量鉴权
- 审计哈希链防篡改（`GET /api/audit/verify`）、审计/巡检/取证落库（H2 文件库，可切 MySQL）
- 真实操作人审计

## S0 · V0.1.0 基线（df05ed3）

- 后端 API + 九页面前端 + 文档中心（Arthas 4.3.5 官方 78 页离线托管 + 全文搜索）+ 安全红线
- 附：仓库文件迁移映射（2026-10-27 重构）：调研报告 → `docs/research/`；发行包解包 →
  `docs/research/arthas-inspect/`；bin/doc 压缩包 → `materials/`；文档解包 → `docs/arthas-docs/4.3.5/`
