# 扁鹊 BianQue · 验收清单（V0.1.x，S1–S7）

> 适用版本：git main 分支（df05ed3 → 17e372f 及之后）。
> 每项标注 【自动】= 已由 `scripts/smoke.sh` / 测试覆盖；【手动】= 浏览器人工确认。

## 1. 环境与启动

| # | 步骤 | 预期 |
|---|------|------|
| 1.1 | `docker compose -f deploy/docker-compose.yml up -d mysql redis` | 两容器 Up，MySQL healthy（8.4.x），`redis-cli ping` → PONG |
| 1.2 | `scripts\start-all.bat`（或手工：`mvn -B package` 后 `java -jar server/bq-server/target/bq-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql --bianque.alerts.ingest-token=<token>`） | 后端 8080 起服，日志含"登录态存储：Redis（重启不丢失）" |
| 1.3 | `cd web && npm run dev` | http://localhost:5173 可访问 |
| 1.4 | `bash scripts/smoke.sh http://127.0.0.1:8080 <ingest-token>` | 全部 [PASS]，最后一行"冒烟验收全部通过" |

## 2. 认证与 RBAC 【自动】

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

## 13. AI 问诊 LLM 接入（S7）

- [ ] 未配置时 chat → 409 并提示先配置；ops 改配置/测试 → 403；viewer chat → 403 【自动】
- [ ] admin 保存配置后 api-key 只回传掩码（`***尾4`），响应不含明文；空 key 提交保留原值 【自动】
- [ ] 思考强度=low/medium 等 → 请求体含 `reasoning_effort` 与 `max_completion_tokens`、不含 temperature/top_p/max_tokens；
      思考强度=关闭 → 请求体含 temperature/top_p/max_tokens、不含 reasoning_effort 【自动】
- [ ] 对话回传 content + reasoning（思考过程折叠块可展开）+ tokens/耗时徽标；每次调用写审计且哈希链校验通过 【自动】
- [ ] 页面：配置面板（管理员可编辑/只读视图）、快捷提问、附加诊断上下文折叠、Enter 发送 【手动】
- [ ] 真实端点验收：将 Base URL/Key/模型 换成内网真实 OpenAI 兼容服务，重跑测试连通与一轮对话 【手动】

## 11. 平台版 Agent（S6）

- [ ] `mvn -B package` 后 `server/bq-agent/target/bq-agent.jar` 产出，`agents/bq-agent.jar` 同步 【自动】
- [ ] 节点注册/心跳接入：无 token/错 token → 401；未注册节点心跳 → 410；用户 Bearer 不能替代节点令牌 【自动】
- [ ] 节点管理页：节点呼吸点"在线"、心跳时间刷新（5s）、展开可见进程列表、"去诊断"跳转带实例 【手动】
- [ ] Agent 通道真实诊断：选 agent 实例执行 `thread -n 5` → 真实线程栈回传，徽标"真实通道 · xxx ms"，
      冷 attach 约 2.5~3.5s、热命令毫秒级；"结束并重置"后靶进程上 arthas 端口消失（stop 生效） 【手动】
- [ ] 巡检覆盖 agent 实例且单实例失败/超时只扣分不炸全局 【自动】
- [ ] 节点掉线（kill agent）20s 后节点页转"离线"，其机队实例自动摘除；重启后同 agentId 恢复 【手动】

## 12. UI 现代化（S6）

- [ ] 全局设计系统生效：靛青主色/胶囊标签/卡片诊籍签/深色终端输出面板/呼吸点在线标识 【手动】
- [ ] 登录页：极光渐变背景 + 脉搏波 logo 卡片；演示账号可点击填充 【手动】

## 10. 已知边界（如实声明）

- 靶场应用/实例/种子告警为内存模拟数据；真实数据（审计/告警/取证/巡检/纳管/Agent 节点台账）已落库
- 同一目标进程只应归属一个通道（本机纳管 or 节点 Agent）：arthas 每 JVM 仅一个 agent，混用时后 attach 端口不生效
- 节点 Agent 为串行 worker（单节点同时执行一条命令）；多节点天然并行
- token 有效期 8h 滑动过期；Redis 不可用时自动回退内存（重启需重登）
- SSO/OIDC 未接（本地账号体系就绪，预留于 UserService）；企微/钉钉不做，仅通用接口
- 常驻快路径仅覆盖 version/jvm/memory/thread 四族（其余命令自动回落一次性通道）
