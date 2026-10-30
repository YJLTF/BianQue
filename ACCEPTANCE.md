# 扁鹊 BianQue · 验收清单（v1.0.0，S1–S8 · v1.1.0 增补 · v1.1.1 增补）

> 适用版本：git main 分支（v1.0.0 及之后）。

## 0.1 v1.1.1 增补验收（布局收纳）

> 前置：`npm run build`（vue-tsc + vite）全绿。

- [x] 【自动】前端构建通过；路由分包不变（ClinicView / AdminView 各自 chunk）
- [ ] 【手动】诊断台收纳：一屏两栏（实例/会话卡含「本机进程纳管」折叠小节）+ 底部「高级工具」页签卡——命令速查行点击跳文档中心，火焰图/火山图页签内容与收纳前一致
- [ ] 【手动】管理设置页签：「安全 / 参数 / 模板 / 用户 / 接入」五页签切换渲染；安全页高危黑名单卡追加拦截/移除回环不受布局调整影响

## 0. v1.1.0 增补验收（R1–R4）

> 服务端 `mvn -B test`（86 用例）+ 前端 `npm run build` 全绿为本节前置。

- [x] 【自动】结果结构化解析：ResultParsers 12 用例（官方 thread 表格/top 块、memory 表格、jvm 键值、monitor 周期行、拟真格式、Welch t、不可识别输出回退文本）
- [x] 【自动】模板中心：TemplateCenterIntegrationTest 4 用例（内置播种、CRUD 全流程、内置不可删可停用、viewer 403、体检/急诊消费）+ CheckupService/RescueService 模板化迁移
- [x] 【自动】参数中心：SysConfigServiceTest 7 用例（默认值/即时生效、范围与非法值、心跳×3 与巡检时限联动、恢复默认、黑名单追加项归一去重/非法输入拒绝）+ API 实测 viewer 403 / 越界 400 / 审计操作人
- [x] 【自动】高危黑名单页面化：BlacklistServiceTest 2 用例（生效=基线∪追加并集、追加加严且基线恒拦截）+ BlacklistIntegrationTest 2 用例（追加→基线三份口径→诊断执行拦截→模板保存 400→清空恢复放行；内置基线不可重复登记、非法 token 拒绝不落值）+ DiagService 追加黑名单即时生效用例
- [x] 【自动】AI 会诊：ConsultIntegrationTest 4 用例（只读自动执行、黑名单拒绝、增强命令审批批准/拒绝、步数上限 8、ai_tool_run 留痕）
- [x] 【自动】文档向量化：EmbeddingIntegrationTest 4 用例（假 /embeddings 端点 → 78 页索引 >100 块、向量检索命中 thread 页、增量重建跳过、admin 门禁、余弦语义）
- [ ] 【手动】诊断台：执行 thread/memory/dashboard/monitor → 「文本/图表」切换出图（状态分布环形/内存水位条形/接口趋势折线）
- [ ] 【手动】性能剖析：开始 CPU 采样 → 停止并出图 → 同源 iframe 渲染火焰图（拟真通道演示形态可用）
- [ ] 【手动】火山图对比：选两实例采样 ≥3 轮 → 散点（log₂FC × −log₁₀p）+ 结论表（样本不足降级）
- [ ] 【手动】AI 会诊：选会诊对象 → 自然语言提问 → 工具步骤时间线 → 最终结论引用命令输出；mock 端点演示工具回路
- [ ] 【手动】模板：管理设置新建带 {{占位符}} 模板 → 诊断台出现 → 点击填参 → 命令填入执行；执行「存为模板」沉淀
- [ ] 【手动】参数配置：改命令超时 → 下一条命令按新值；改心跳间隔 → 探针日志出现新节奏（v1.1.0+ 探针）
- [ ] 【手动】高危黑名单管理卡：管理设置追加 `jad` → 基线卡/管理卡即时出现追加项 → 诊断台执行 `jad com.demo.OrderService` 被拦截（提示含生效合集）→ `ognl` 仍恒拦截 → 管理卡 × 移除 → 复测恢复放行；模板编辑器保存含 `jad` 的命令 → 400；审计中心留痕
- [ ] 【手动】登录限速：同账号+IP 连续 5 次错误口令 → 第 6 次 429，5 分钟后恢复；审计「安全」来源留痕
- [ ] 【手动】诊断台「导出报告」下载 .md（实例/时间/命令/输出/指标）；AI 回答 Markdown 渲染 + 代码复制
- [ ] 【手动】诊断台「AI 分析」就地流式分析 → 「转问诊台追问」上下文带入
- [x] 【自动】登录限速（AuthFlow 扩展）：（随集成测试口径覆盖 403/401，429 手动核对）
- [x] 【自动】全版本号 1.1.0：pom ×4 / bq-agent VERSION / web package.json / .env.example BQ_VERSION

---

> 以下为 v1.0.0 原始清单（S1–S8）。

> 适用版本：git main 分支（v1.0.0 及之后）。
> 每项标注 【自动】= 已由 `scripts/smoke.sh` / 测试覆盖；【手动】= 浏览器人工确认。

## 1. 环境与启动

| # | 步骤 | 预期 |
|---|------|------|
| 1.1 | `docker compose -f deploy/docker-compose.yml up -d mysql redis` | 两容器 Up，MySQL healthy（8.4.x），`redis-cli ping` → PONG |
| 1.2 | `scripts\start-all.bat`（或手工：`mvn -B package` 后 `java -jar server/bq-server/target/bq-server-1.1.0.jar --spring.profiles.active=mysql --bianque.alerts.ingest-token=<token>`） | 后端 8080 起服，日志含"登录态存储：Redis（重启不丢失）" |
| 1.3 | `cd web && npm run dev` | http://localhost:5173 可访问 |
| 1.4 | `bash scripts/smoke.sh http://127.0.0.1:8080 <ingest-token>` | 全部 [PASS]，最后一行"冒烟验收全部通过" |

## 2. 认证与 RBAC 【自动】

> 本节与 §3 拟真项按**演示模式**（`BIANQUE_DEMO_ENABLED=true`）核对；生产模式仅 admin 一个账号
> （口令由 `BIANQUE_ADMIN_PASSWORD` 引导），无拟真通道，演示账号不存在。

- [ ] 未登录访问业务 API → 401；错误口令 → 400
- [ ] admin/admin123 登录成功（ADMIN），可见"管理设置-用户与角色"（3 账号 + 权限点）
- [ ] ops/ops123（OPERATOR）可执行诊断；viewer/viewer123（VIEWER）执行被拒 403，提示"权限不足（只读 无 diag:execute）"
- [ ] 顶栏显示真实登录人；退出后访问任何页面回到登录页

## 3. 诊断台

- [ ] 模拟实例（如 order-app/pod-3）执行 `thread -n 5`：绿色标签显示"拟真演示 · xxx ms" 【自动】
- [ ] 输入 `ognl '@System@exit(0)'` 执行 → 前端弹高危确认层，后端黑名单拦截 【自动】
- [ ] 会话倒计时显示（30 分钟），"结束并重置"可回收

## 4. 真实诊断闭环（需靶场进程）

- [ ] 启动靶场：`java -jar docs/research/arthas-inspect/math-game.jar`
- [ ] 诊断台"本机进程纳管"卡片列出本机 JVM；对 math-game 点"纳管"→ 出现"已纳管"
- [ ] 选中 math-game 实例执行 `thread -n 3`：标题变"执行结果（真实通道 · arthas-boot）"，输出为真实线程栈（demo.MathGame）
- [ ] 首次执行后再次执行热命令（thread/jvm/memory/version）：耗时 <800ms（常驻快路径）；结果区徽标"真实通道"
- [ ] 管理设置页执行模式说明：热命令走常驻 HTTP，其余命令自动回落一次性通道
- [ ] "结束并重置"会话 → math-game 上 arthas agent 端口关闭（`netstat -ano | findstr <pid>` 无 3658/8563 类监听）

## 5. 审计与防篡改 【自动】

- [ ] 审计中心记录含真实操作人（admin(管理员(演示))），高危拦截也留痕（result=已拦截）
- [ ] 每行有哈希列（8 位摘要），悬停显示 prev/hash
- [ ] "校验哈希链" → 绿色横幅"完整性校验通过：已核验 N 条"
- [ ] （破坏性验证，可选）直接改库任一条 command 后再校验 → 红色横幅指出断裂 seq

## 6. 持久化与重启

- [ ] 执行若干命令后重启后端 → 用**原 token** 直接访问（Redis 会话存活，无需重新登录）
- [ ] 已纳管实例仍在（MySQL managed_instance），免重新 attach
- [ ] 审计哈希链校验 valid=true（跨重启）；巡检历史（巡检体检页右侧）保留
- [ ] MySQL 中可见表：audit_log / checkup_run / rescue_report / alert_event / managed_instance / users / roles / permissions / user_roles / role_permissions

## 7. 急诊取证与告警接入

- [ ] 演示告警（AL-100x）一键取证 → 现场报告（结论/步骤/内存水位/线程 TOP）
- [ ] 【自动】入站接入：`POST /api/alerts/ingest`（X-Alert-Token）→ 列表出现"已接入"徽标告警；错 token → 401；未配置 token → 403
- [ ] 对接入告警一键取证 → 状态回写"已取证"（MySQL alert_event.status）
- [ ] 出站预留：配置 `bianque.alerts.webhook-url` 后，新告警/取证完成向该 URL POST JSON（报文见急诊页说明卡）

## 8. 文档中心

- [ ] 78 页 Arthas 4.3.5 官方文档离线可读（iframe 直出）、10 分类、搜索命中、命令风险徽标
- [ ] 诊断台"文档即命令"行点击跳转对应文档

## 9. 安全红线（发布门禁）

- [ ] 平台自身：/api/** 全量鉴权（除登录/探活/告警接入三个显式豁免点）
- [ ] 高危命令（ognl/redefine/retransform/mc/groovy/heapdump）全链路拦截并审计
- [ ] 真实通道会话超时（30 分钟）自动回收并真实 stop 卸载 agent
- [ ] `bianque.execution.local-enabled=false` 时纳管接口 403、本机通道不装配（生产基线）
- [ ] 敏感配置项（DB 口令 / ingest-token）均支持环境变量覆盖，仓库不含真实口令

## 10. 平台版 Agent（S6）

- [ ] `mvn -B package` 后 `server/bq-agent/target/bq-agent.jar` 产出，`agents/bq-agent.jar` 同步 【自动】
- [ ] 节点注册/心跳接入：无 token/错 token → 401；未注册节点心跳 → 410；用户 Bearer 不能替代节点令牌 【自动】
- [ ] 节点管理页：节点呼吸点"在线"、心跳时间刷新（5s）、展开可见进程列表、"去诊断"跳转带实例 【手动】
- [ ] Agent 通道真实诊断：选 agent 实例执行 `thread -n 5` → 真实线程栈回传，徽标"真实通道 · xxx ms"，
      冷 attach 约 2.5~3.5s、热命令毫秒级；"结束并重置"后靶进程上 arthas 端口消失（stop 生效） 【手动】
- [ ] 巡检覆盖 agent 实例且单实例失败/超时只扣分不炸全局 【自动】
- [ ] 节点掉线（kill agent）20s 后节点页转"离线"，其机队实例自动摘除；重启后同 agentId 恢复 【手动】

## 11. UI 现代化（S6）

- [ ] 全局设计系统生效：靛青主色/胶囊标签/卡片诊籍签/深色终端输出面板/呼吸点在线标识 【手动】
- [ ] 登录页：极光渐变背景 + 脉搏波 logo 卡片；演示账号可点击填充 【手动】
- [ ] 工作台图标磁贴统计卡；节点管理页结构（统计卡/节点表展开进程/三步接入指引） 【手动】

## 12. AI 问诊 LLM 接入（S7）

- [ ] 未配置时 chat → 409 并提示先配置；ops 改配置/测试 → 403；viewer chat → 403 【自动】
- [ ] admin 保存配置后 api-key 只回传掩码（`***尾4`），响应不含明文；空 key 提交保留原值 【自动】
- [ ] 思考强度=low/medium 等 → 请求体含 `reasoning_effort` 与 `max_completion_tokens`、不含 temperature/top_p/max_tokens；
      思考强度=关闭 → 请求体含 temperature/top_p/max_tokens、不含 reasoning_effort 【自动】
- [ ] 对话回传 content + reasoning（思考过程折叠块可展开）+ tokens/耗时徽标；每次调用写审计且哈希链校验通过 【自动】
- [ ] 页面：配置面板（管理员可编辑/只读视图）、快捷提问、附加诊断上下文折叠、Enter 发送 【手动】
- [ ] 真实端点验收：将 Base URL/Key/模型 换成内网真实 OpenAI 兼容服务，重跑测试连通与一轮对话 【手动】

## 13. 一键部署与节点探针（S8）

- [ ] `cd deploy && AGENT_TOKEN=<令牌> docker compose -f docker-compose.prod.yml up -d --build`
      产出 bianque/{web,server,probe}:1.0.0 三镜像（76/597/631MB），全栈起服 【手动】
- [ ] 走 Nginx 端口：前端 200、/api/health UP、登录+机队可用、/api/docs/catalog 返回 262KB 【手动】
- [ ] 探针容器（--profile probe）经 compose 网络注册，节点管理页可见、心跳刷新 【手动】
- [ ] Linux 主机裸机探针：`sudo ./deploy/probe/install.sh --server ... --token ... [--user <应用用户>]`
      → systemd 常驻、节点上线、可对宿主 JVM 执行真实诊断（含非 root 应用：发行目录 a+rX 或同用户运行） 【手动，Linux 环境】
- [ ] 容器化 server 本机纳管通道已关闭（bianque.execution.local-enabled=false） 【自动，compose command】

## 14. 已知边界（如实声明）

- 模拟靶场/种子告警/演示账号仅存在于演示模式（`BIANQUE_DEMO_ENABLED=true`）；生产模式实例只来自真实探针上报。
  真实数据（审计/告警/取证/巡检/纳管/Agent 节点台账/用户）已落库
- 同一目标进程只应归属一个通道（本机纳管 or 节点 Agent）：arthas 每 JVM 仅一个 agent，混用时后 attach 端口不生效
- 节点 Agent 为串行 worker（单节点同时执行一条命令）；多节点天然并行
- token 有效期 8h 滑动过期；Redis 不可用时自动回退内存（重启需重登）
- SSO/OIDC 未接（本地账号体系就绪，预留于 UserService）；企微/钉钉不做，仅通用接口
- 常驻快路径仅覆盖 version/jvm/memory/thread 四族（其余命令自动回落一次性通道）
