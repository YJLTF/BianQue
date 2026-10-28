# 扁鹊 BianQue · 变更记录（CHANGELOG）

按迭代追加；每个迭代的验收细目见 [ACCEPTANCE.md](ACCEPTANCE.md)，部署操作见 [deploy/README.md](deploy/README.md)。

## 修复 · 搜索键盘导航 + 巡检分数精度 + 用户管理落地

- **全局搜索键盘失效**：↑↓/Enter 监听绑在不可聚焦的结果容器上，只有 Esc 生效；改绑到搜索输入框并修
  实例列表异步晚到不进结果的问题（flat 改响应式 computed）。↑↓ 选择 → Enter 跳转全链路实测通过
- **巡检平均分精度**：历史记录显示 87.14285714…；后端落库/出库统一 round2，前端展示最多 2 位小数
- **用户与角色管理落地**（原为只读台账）：新增用户（账号/姓名/初始密码/角色）、重置密码、启用停用、
  在线调整角色（ADMIN/OPERATOR/VIEWER）；内置 admin 账号禁止停用与改角色（防误降级锁死平台，
  后端强校验 + 前端禁用）；集成测试新增管理全流程用例（创建→改角→重置密码→停用→登录拒绝），29 项全绿
- 诊断台实例下拉标注来源：本机纳管「· 本机」、Agent 心跳「· Agent 节点」，未标注为演示靶场实例

## 修复 · 告警级别英文 + 全局搜索落地 + 分页中文化

- **告警级别归一化**：外部系统经 ingest 推入的 Warning/Critical/P0 等英文级别值原样入库展示
  （急诊取证首行出现「Warning」）；接入时统一映射为 严重/警告/提示 三级，存量脏数据一并修正
- **全局搜索落地**：顶栏搜索框此前为无功能占位。新增 Ctrl+K 命令面板（GlobalSearch），
  聚合五类结果：页面 / 实例（在线状态）/ Arthas 命令 / 场景模板 / 文档全文；
  ↑↓ 选择、Enter 打开，选中实例跳诊断台并带实例、命令/模板直接填充命令框
- **分页中文化**：Element Plus 全局配置 zh-cn 语言包（「Total 369」「20/page」→「共 369 条」「20 条/页」），
  审计中心默认改为 10 条/页（可选 10/20/50/100/200）

## 优化 · 全站文案精简（中文优先）

- 卡片标题去冗余括号注释：「本机进程纳管（真实通道）」「用户与角色（RBAC 五表）」「MCP 个人版接入（零开发）」
  「节点接入指引（平台版 Agent）」等统一为短标题，技术细节留在正文提示行
- 删除版本号/路线图式文案：「V0.1.0 平台策略」「S1 起…S2 规划：每日 02:00 调度、企微推送」等过时声明
- 术语统一中文化：拟真演示→演示模拟、as.sh 真实→真实执行、接管→纳管、Agent 4.3.5→Arthas 4.3.5；
  思考强度选项「low · 低」中英重复改为「low（较低）」格式
- 顺手修复：诊断台「命令速查」同样存在裸 fetch 无 token 问题（目录加载 401 致表格空白），改用带鉴权客户端
- 全站 10 个页面逐一回归渲染

## 优化 · 审计中心数据库侧分页

- 原实现 `findAll()` 全表载入内存排序截断（最多 500 条），前端固定取前 100 条、更早记录不可见
- 改为 JPA `Page` 查询（seq 倒序 + 来源/操作人条件在 SQL 侧过滤），接口返回 `{total, records}`
  并新增 page/size 参数（size 上限 200）；`AuditTrail.query` 接口签名不变，复用分页实现
- 前端上 `el-pagination`（20/50/100/200 每页可切，总数/跳页），切过滤条件自动回第 1 页
- 实测：369 条记录翻页/切页大小/来源过滤正常；集成测试新增分页用例（自播种 5 条断言
  total 一致性与 seq 倒序无重叠），28 项测试全绿

## 修复 · 文档中心空白（0 页）

- **目录接口 401**：DocsView 用裸 `fetch` 调 `/api/docs/catalog|search` 未携带登录 token，被全量鉴权拦截后
  静默降级为空列表；改走带 Bearer 的 `http` 客户端，加载失败显式报错
- **整页加载 404**：`/docs` 既是前端路由又被 vite/nginx 代理到后端静态文档，刷新即撞 Whitelabel 404；
  代理范围收窄为实际静态前缀 `/docs/4.3.5/`（`vite.config.ts` + `deploy/docker/nginx.conf`）
- **正文无样式/404 视图**：离线文档为 VuePress 构建产物，资源与路由 base 均按站点根（`/assets/...`、
  `"base":"/"`）打包，挂到 `/docs/4.3.5/` 子路径后全部失配；对 235 个 HTML/CSS/app.js 做一次性基路径
  适配（`/docs/4.3.5/` 前缀 + siteData base），官方站样式、正文、站内路由在子路径下完整可用
- 实测（:80 生产视图）：目录 78 页/10 分类、搜索、watch 正文带官方样式渲染；dev(:5174) 一致

## 修复 · 急诊取证活动告警表窄屏溢出

- 活动告警 6 列表格在半宽卡片里放不下，「一键取证」按钮被截断：压缩列宽
  （时间/级别/来源/操作 + 文本列 `show-overflow-tooltip` 省略号）、单元格内边距收紧、按钮改短为「取证」
- 分栏断点改按表格实际所需宽度自定义（视口 ≥1560px 左右分栏，否则上下堆叠全宽），
  实测 993/1280/1540 堆叠完整、1560 起分栏无横向滚动；取证流程回归通过

## 优化 · AI 问诊模型接入卡片可折叠

- 卡片头部改为可点击折叠条：折叠时仅占一行，右侧显示「已配置/未配置 + 模型名 + 思考强度」摘要，
  参数设好后折叠不再占用版面；箭头旋转指示，悬停可看完整摘要
- 折叠时聊天区自动占满全宽（气泡限宽 780px 保证可读），展开恢复 10/14 分栏
- 折叠状态记忆在 localStorage；首次访问按配置状态智能默认（未配置展开引导、已配置默认折叠）

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
