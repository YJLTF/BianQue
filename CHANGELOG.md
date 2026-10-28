# 扁鹊 BianQue · 变更记录（CHANGELOG）

按迭代追加；每个迭代的验收细目见 [ACCEPTANCE.md](ACCEPTANCE.md)，部署操作见 [deploy/README.md](deploy/README.md)。

## v1.0.0 · 首个正式版（6bf0a9e 之后）

- **版本统一 1.0.0**：四模块 pom 0.1.0-SNAPSHOT → 1.0.0、web package.json、构建产物名与
  Dockerfile/脚本引用同步；镜像 tag 版本化 `bianque/server|web|probe:1.0.0`（compose `BQ_VERSION` 可配）
- **版本可观测**：`/api/health` 返回 `version` 字段（部署校验依据）；登录页与侧栏显示
  v 版本号（vite define 注入 package.json，单一来源）
- **配置模板化**：新增 `deploy/.env.example`（版本/令牌/LLM/口令/端口全量注释），
  compose 支持 `LLM_BASE_URL`/`LLM_API_KEY` 预置；仓库与镜像不含任何真实密钥的约束保持不变
- **文档整理**：根 README 重写（能力总览 / 文档地图 / 三种快速开始 / 红线与边界，
  S1–S7 迭代流水账归档至本文件）；deploy/README 新增「离线交付包部署」形态与 .env 流程；
  新增使用指南 [`docs/USAGE.md`](docs/USAGE.md)（角色权限 / 十页导览 / 三个典型工作流 / AI 与 MCP 接入 / FAQ）
- **发布交付**：`dist/bianque-images-1.0.0.tar.gz`（server/web/probe/mysql/redis 五镜像 docker save 导出，
  内网离线 `docker load` 即用）+ `dist/bianque-1.0.0/`（compose 配置、.env.example、探针 jar/安装器、
  部署与使用指南副本）；交付物不入库（.gitignore dist/）
- 后端测试 33 项全绿
- 侧栏副标题精简为「线上诊断平台」（版本号与 Arthas 标识只在顶栏 tag 展示，消除横向滚动与重复）
- **无效目录清理**：移除被服务端 `DocsService` 取代的 S0 文档管线（`docs-center/`、
  `deploy/docs-site/` 入库产物）与 arthas 运行时残留空目录（`arthas-output/`、`agents/arthas-output/`，
  忽略规则保留、运行时自动重建）；`data/`（开发 H2 库）与 `agents/`（探针下载分发）为有效目录保留
- **探针独立镜像包**：新增 `dist/bianque-probe-1.0.0.tar.gz`（186MB，仅探针镜像）——目标主机只需
  `docker load` + 一条 `docker run --pid=host` 即接入，附 `probe/README-探针镜像.md` 使用说明；
  交付前用 1.0.0 镜像重建本机 bq-probe 容器，节点重新注册在线（验证探针镜像端到端可用）
- **节点管理支持删除离线节点**：新增 `DELETE /api/agents/{agentId}`（仅 admin:user，仅离线节点可删，
  在线删除返回 409、重复删除 404、非管理员 403）；节点台账、内存进程表与机队实例一并清理并审计留痕；
  前端操作列仅管理员可见、在线行按钮禁用带提示、删除二次确认；新增集成测试 4 断言（34 项全绿）
- **诊断台应用/实例下拉可读性优化**：选中态从超长原始标识（org.eclipse.equinox.launcher_1.8.0…）
  改为紧凑标识（短应用名 · 节点，窄栏 12px 小字、预算内逐级缩短应用名）；展开为两行明细
  （应用全名 / 实例 ID + 通道 · 主机 · pid · 离线标记）；卡片下方新增「当前：」完整标识行（悬停看全文）

## 功能 · AI 问诊接入文档知识库（检索增强）

- **文档中心 78 页官方文档成为 AI 知识库**：`DocsService` 新增 `searchKnowledge`——问题提取检索词
  （ASCII 命令/类名词元 + 中文双字组滑窗，带停用词表），多路打分合并（slug/标题强加权、正文按出现频次
  封顶计分），返回 Top-4 命中页；`LlmService` 对话前自动检索并将命中片段注入 system prompt
  （单页截 2500 字、总量 12k 兜底），寒暄/无关问题零命中零注入，不干扰纯对话
- **引用可溯源**：回答气泡下方展示「参考文档」标签（watch/命令列表…），点击直达文档中心对应页并
  高亮定位；chat 响应新增 `refs` 字段回传命中列表
- **顺带修复存量缺口**：文档中心此前从不解析 `?page=` 深链参数——诊断台命令速查行点击、全局搜索文档
  命中、AI 参考文档三处跳转都只落到目录页；DocsView 现支持 `?page=doc/xxx.html` 深链自动选中并打开正文
- 测试：`searchKnowledge` 英文命令/中文问题命中排序、无关问题零命中；chat 注入与引用回传、无命中不注入
  （假 OpenAI 端点集成测试），33 项全绿
- 环境修复：容器内 LLM baseUrl 从容器内不可达的 `127.0.0.1:8901` 改为 `host.docker.internal:8901`
  （宿主机 `scripts/dev-llm-mock.py`），容器内 AI 对话链路恢复可用

## 修复 · 诊断执行 loading 反馈 / 节点表格换行

- **诊断台执行无等待反馈**：点击「执行诊断」后到结果返回前界面毫无变化，真实 Agent 通道秒级等待
  像是没点上；执行期间按钮转 loading 并禁用（防重复提交），执行结果卡片叠加遮罩
  「命令执行中，请稍候…」，成功/失败（含黑名单拦截）均在遮罩结束后原位渲染
- **接入节点表格列换行**：JDK 列宽 70 放不下三段版本号（17.0.20.1 折行）；列宽调至 92，
  节点/主机列补 show-overflow-tooltip，长值省略号悬停展示，整表保持单行

## 修复 · 用户表布局 / 节点展开保持 / 文档冗余文案

- **用户与角色表横向滚动**：6 列表格挤在半宽卡片里，操作按钮被截断；卡片移出双栏改为整页全宽布局，
  1270px 宽度下无横向滚动，重置密码/停用按钮完整可见
- **节点管理展开自动收起**：5s 轮询整体替换数据导致展开状态丢失；表格加 row-key + 受控展开键
  （expand-row-keys），轮询后展开行保持不收
- 文档中心删除「共 78 页 · 中/EN」冗余标签

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
