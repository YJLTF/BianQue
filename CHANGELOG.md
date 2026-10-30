# 扁鹊 BianQue · 变更记录（CHANGELOG）

按迭代追加；每个迭代的验收细目见 [ACCEPTANCE.md](ACCEPTANCE.md)，部署操作见 [deploy/README.md](deploy/README.md)。

## v1.1.1 发布（本次）：诊断台与管理设置布局收纳

- **诊断台**：「本机进程纳管」并入「应用 / 实例」卡（折叠小节，标题带进程数）；右栏「命令速查」常驻列撤销，与第二行火焰图/火山图合并为底部「高级工具」通栏页签卡（命令速查 / 性能剖析 / 双实例对比）；主体列 10→18 加宽，两行 8 卡收为一屏 5 块；watch/trace 限次限频提示并入命令构建卡说明行
- **管理设置**：8 张平铺卡改「安全 / 参数 / 模板 / 用户 / 接入」五页签——安全页 = 安全基线 + 权限矩阵（并排）+ 高危命令黑名单（通栏），管理动作首屏直达，参考型内容（Agent 安装/MCP）归档「接入」；联动文案同步（参数页提示黑名单移至「安全」页、基线追加项 tag 悬停说明）
- **镜像构建改「宿主产物装载」快路径**：三个 Dockerfile 从容器内多阶段构建（mvn/npm 在容器内联网冷爬依赖，版本变更即缓存失效，实测 20+ 分钟）改为宿主 `mvn clean package` / `npm run build` → 运行时基础镜像 COPY 装载（实测三镜像合计约 20 秒）；定位对齐「Docker 只为内网快速移植运行，构建依赖开发机」；交付前以探针字节码 major 55 复核防宿主 IDE 增量产物混入（v1.1.0 曾因此漏出 Java 11 基线违规，靠容器全量编译才拦截）；.dockerignore 相应只放行 jar 与前端 dist，中间产物不进上下文
- 文档：USAGE 页面地图与火焰图/火山图操作入口同步；版本号 1.1.1 全量对齐（pom ×4 / AgentMain VERSION / web package.json / .env.example / compose 默认）
- 实测：`npm run build`（vue-tsc）全绿；本地栈（1.1.0 后端 + dev 前端）浏览器冒烟——诊断台两栏 + 三页签切换内容正确，管理设置五页签逐一渲染、安全页基线/RBAC/黑名单卡数据齐全；镜像装载实测 server 599MB / web 77MB / probe 669MB，web 新 bundle 与 1.1.1 版本串入包，探针 jar 版本串与字节码基线复核通过

## v1.1.0 发布：模板中心 / 参数中心 / AI 会诊 / 向量检索 / 火焰图 / 火山图 / 可视化 / 安全加固

> 规划与阶段验收见 [plan/BianQue-开发计划-v1.1.md](plan/BianQue-开发计划-v1.1.md)；本节为 v1.1.0 全量交付记录。

### R1 · 底座

- **修复 · 诊断执行「timeout of 15000ms exceeded」**：全局 axios 15s < 服务端命令超时 60s（Agent 通道等待上限 90s），长命令被前端先行中断。`executeDiag` 单独 150s 预算；同批排查：本机纳管 attach 120s、急诊取证/档案扫描 300s。配套闭环：参数中心命令超时上限收为 120s（120+30 ≤ 150）
- **结构化解析**：bq-core `ResultParsers`（thread 官方表格/top 块/拟真、memory 三形态、jvm 键值、monitor 周期行、Welch t 检验）；`/api/diag/execute` 响应附带 `metrics`，不可解析回退文本视图
- **AI 回答 Markdown 渲染**：`MdView` 共享组件（markdown-it + highlight.js + DOMPurify，代码高亮+复制条，外链 noopener）；问诊台与诊断台 AI 面板接入；按路由分包

### R2 · 模板中心

- `diag_template` 实体 + `/api/templates` CRUD（写操作 diag:execute 门禁、审计留痕）；内置 8 模板播种（诊断台 6 场景 + 默认体检 + 默认急诊），可停用/复制不可删除
- **占位符参数化**：命令支持 `{{类名}}`/`{{方法名}}` 等，诊断台应用模板弹表单填参（上次填值记忆）
- **存量迁移**：体检命令集（CheckupService）与急诊取证序列（RescueService，含步骤名）改读模板中心，缺省回落 v1.0 行为；诊断台模板后端加载（旧服务端兜底内置）；管理设置「诊断模板」管理区块；执行结果一键「存为模板」沉淀

### R3 · AI 问诊进化

- **文档向量化**：`llm_config` 扩展 embedding 端点/密钥/模型/维度（页面配置、掩码口径一致）；`doc_embedding` 分块索引（内容 hash 增量、启动后台建索引、`/api/llm/embeddings/{reindex,status}` 仅 admin）；**混合检索**：向量 Top-K + 关键词 Top-K RRF 融合注入，端点未配置/故障自动回退关键词（内网离线零负担）
- **AI 会诊**（选中实例 + 自然语言 → AI 执行分析）：`ConsultService` function calling 循环（步数 ≤8 服务端强制）；工具 list_instances/describe_instance/run_diag_command/run_checkup/search_docs；安全边界——命令全量过白名单，黑名单 AI 发起直接拒绝**不可放行**，增强命令（watch/trace/tt/monitor/stack/profiler/jfr）审批卡（60s 超时默认拒绝）；每步落 `ai_tool_run` + 审计哈希链（来源「AI会诊」）；SSE 事件 start/tool/confirm_required/reasoning/content/done/error；前端会诊对象选择器 + 步骤时间线 + 审批卡；端点不支持 tools 明确降级提示
- 诊断台执行结果一键「AI 分析」就地流式分析 + 「转问诊台追问」上下文交接（sessionStorage）

### R4 · 专业场景可视化 + 加固

- **可视化**：ECharts 按需引入（诊断台路由分包）；诊断台「文本/图表」双视图——线程状态分布环形图、CPU TOP 条形图、内存池水位条形（≥85% 红/≥70% 黄阈值着色）、monitor 接口趋势双轴折线
- **火焰图**：profiler start/stop 经命令通道（本机/探针/拟真一致），产物 `cat` 回传落 `diag_artifact`（≤20MB），`/api/diag/artifact/{id}` 同源 iframe 渲染（登录门禁 + nosniff）；拟真通道内置演示形态
- **火山图（双实例对比诊断）**：`/api/diag/compare` 目标 vs 基线实例同命令集采样 N 轮（memory/dashboard/jvm），逐指标 log₂ 倍数变化 × Welch t 显著性；ECharts 散点（红=目标显著升高/蓝=显著降低/灰=不显著）+ 结论表；样本 <2 窗口自动降级"仅倍数变化"
- **登录限速**：同账号+IP 60s 内失败 ≥5 次锁 5 分钟（429），成功清零；触发入审计（来源「安全」）
- **数据保留**：诊断工件默认 7 天 / 命令单默认 14 天（参数中心可配），每 6 小时清理；审计哈希链合规留痕不自动清理
- **报告导出**：诊断台一键导出 Markdown 报告（实例/时间/通道/命令/输出/指标摘要）

### 参数中心（页面可配置）

`sys_config` + `SysConfigService`（注册表定义键/默认/范围/生效语义，启动播种、缓存写后失效、免重启生效）；首批 10 项：命令执行超时、会话自动回收、探针心跳间隔、在线判定窗口、登录令牌有效期、巡检总时限/单实例时限、工件保留天数、命令单保留天数、**高危黑名单追加命令**（首个 list 型参数，唯一下放页面管理的安全项，仅可加严）；关联校验（在线窗口 ≥ 心跳×3、巡检总时限 ≥ 单实例时限）；管理设置「参数配置」区块（行内编辑/已改标记/恢复默认/最近修改人）；红线项（端口绑定/强制认证/审计开关/高危黑名单**内置基线**）不进注册表天然不可配；AuthInterceptor 收严 `/api/admin/**` 一律 admin:user
- **探针动态心跳**：bq-agent 解析 register/heartbeat 响应 `heartbeatIntervalSeconds`（夹取 2~60s）实时跟随；旧探针向后兼容

### 高危黑名单页面化管理（仅可加严）

- **`BlacklistService` 唯一裁决点**：生效黑名单 = 内置基线（ognl/redefine/retransform/mc/groovy/heapdump，恒拦截、不可削减、不可放行）∪ 参数中心追加项 `diag.blocked-extra-commands`；人工诊断（诊断台/体检/急诊/剖析）与 AI 会诊（run_diag_command）统一经此判定，两链路口径一致
- **管理设置「高危命令黑名单」卡**：内置基线标签（深色、不可移除）+ 追加项标签（× 移除即恢复放行）+ 批量添加（逗号/空格/全角分隔、大小写归一、去重、命中内置基线拒绝重复登记、非法 token 拒绝、至多 50 条）+ 恢复默认（清空追加项）；所有改动入审计哈希链；安全基线卡与 `/api/baseline` 暴露三份口径（内置基线 / 追加项 / 生效合集）
- **拦截面全覆盖**：诊断台人工执行、AI 会诊（黑名单不可审批放行不变）、模板保存 fail-fast 校验（含追加项命中即 400）；`/api/diag/execute` 拦截信息展示生效合集
- 典型用途：按组织策略把 `jad`/`sc` 等反编译/类信息探测类只读命令加严为禁用

### 质量与实测

- 服务端测试 45 → **86**（ResultParsers 12、TemplateCenter 4、SysConfigService 7、Embedding 4、Consult 4、BlacklistService 2、BlacklistIntegration 2、DiagService 追加黑名单用例 + 存量）；`mvn -B test` 全绿
- 编译确定性：maven-compiler-plugin 显式 `-parameters`（Spring 反射参数名依赖，曾被 IDE 增量产物掩盖）
- 实测：本地栈（1.1.0 jar + dev-llm-mock 增强 tools 回路）API 全链路 + 浏览器冒烟（参数区块/AI 分析/会诊/图表/火焰图/火山图/模板填参/限速 429），详见 ACCEPTANCE §0
- 实测（黑名单闭环）：管理页追加 `jad` → 基线卡/管理卡即时出现追加项 → 诊断台执行 `jad com.demo.OrderService` 被拦截（提示含生效合集 7 条）→ 管理卡 × 移除（显示最近修改人/时间）→ 复测恢复放行
- 交付构建：server/web/probe:1.1.0 三镜像容器化构建通过（探针 Java 11 基线修复见修复记录）；镜像冒烟——容器化 server 对真实 MySQL 8.4 + Redis 7.4 起服 `/api/health` 返回 1.1.0 UP，web(nginx) 反代 `/api` 与平台页全链路打通；交付包 `dist/bianque-1.1.0/`（config/guides/probe/交付清单/SHA256SUMS）+ 五镜像/探针独立两份 tar.gz
- 已知边界：诊断台命令速查表与前端高危确认弹窗按内置静态字典展示，追加项拦截以后端为准（报错文案含生效合集）；调大命令超时上限须同步评估三处（参数上限/通道余量/前端预算）；火山图统计口径为参考性对比；旧探针忽略心跳下发字段

### 修复 · 探针 Java 11 基线（v1.1.0 交付构建期发现）

- 容器化构建实证：v1.1.0 动态心跳代码用了 `instanceof` 模式匹配（Java 16+），违反 bq-agent 的 `maven.compiler.release=11` 基线（目标主机 JDK 11/17/21 混部）；宿主此前未暴露——IDE（ECJ）增量产物写进 target/classes 掩盖了 Maven 增量编译，同 `-parameters` 被掩盖同款坑
- 修复：改经典强转，代码内注释基线约束；清 target 后 release=11 真编译通过，字节码 major 55（Java 11）复核；交付包 probe/bq-agent.jar 同步更新

- **修复 · 诊断执行「timeout of 15000ms exceeded」**：全局 axios 15s 超时 < 服务端命令超时 60s（Agent 通道等待上限 90s），长命令必被前端先行中断。`executeDiag` 单独 150s 预算（同批排查：本机纳管 attach 120s、急诊取证/档案扫描 300s 逐一覆盖；巡检/LLM 原本就有）。配套闭环：参数中心命令超时上限收为 120s（120+30 ≤ 150），杜绝"配置调大又复现"
- **功能 · 参数中心（页面可配置）**：`sys_config` 表 + `SysConfigService`（注册表定义键/默认/范围/生效语义，启动播种、内存缓存写后失效、免重启生效）；首批 7 项——命令执行超时（替换 DiagService 硬编码 60s）、会话 TTL、**探针心跳间隔**（register/heartbeat 响应下发）、**心跳离线判定窗口**（AgentDirectory 动态读）、登录令牌 TTL（TokenStore 动态读）、巡检总时限/单实例时限；关联校验（在线窗口 ≥ 心跳×3、巡检总时限 ≥ 单实例时限）；红线项（端口绑定/强制认证/审计开关/高危黑名单）不进注册表天然不可配。管理设置新增「参数配置」区块（行内编辑/已改标记/恢复默认/最近修改人）。AuthInterceptor 收严：`/api/admin/**` 一律 admin:user（原仅 /users）
- **功能 · 探针动态心跳**：bq-agent 解析服务端下发 `heartbeatIntervalSeconds`（夹取 2~60s）实时调整心跳节奏；旧探针忽略该字段向后兼容
- **功能 · 诊断台一键「AI 分析」**（AI 贯穿核心区域第一步）：执行结果就地流式分析——自动构造问题（实例/命令/分析维度）+ 输出作 context 走 `/api/llm/chat/stream`；思考过程可折叠、参考文档可跳转、可停止（AbortController）；完成后「转问诊台追问」经 sessionStorage 交接带上下文跳转 `/ai`（自动展开上下文框）
- **功能 · AI 回答 Markdown 渲染**：新增 `MdView` 共享组件（markdown-it + highlight.js + DOMPurify 消毒，`html:false`，外链 `_blank+noopener`，代码块高亮+复制条）；问诊台助手消息与诊断台 AI 面板接入；按路由分包不进主包
- 测试 45 → **50**（SysConfigServiceTest ×5：默认值/即时生效、范围与非整数拒绝、心跳-窗口联动、巡检时限联动、恢复默认）；`DiagService` 构造器接入参数中心（测试改内存模式）
- 实测：本地栈（新 jar 8090 + dev-llm-mock 8901）API 全链路——config 增删改查/越界 400/关联约束拒绝/viewer 403/审计操作人落库；拟真 inv-2 执行 `thread -n 5` → AI 分析流式出结论（浏览器截图确认 Markdown 渲染）→ 转问诊台上下文带入 → 追问成功；管理页参数区块编辑/恢复默认实测
- 已知边界：前端 executeDiag 预算 150s 为常量，若未来调大命令超时上限须同步评估三处（服务端参数上限/通道余量/前端预算）

## 修复 · 文档中心正文 iframe 显示「localhost 拒绝连接」（上次）

- 根因：此前安全加固在 nginx 加的全局 `X-Frame-Options: DENY` 连**同源 iframe** 一并拦截——文档中心正文
  恰是同源 iframe（`/docs/4.3.5/**` 经 nginx 代理），被自己平台的防嵌出头挡住；Chrome 对被拒渲染的提示
  即「xxx 拒绝连接」，与端口拒绝表象相同，易误判为服务不可用（实际 /api、静态页 200 全通）
- 修复：X-Frame-Options 改为 **SAMEORIGIN**——本站可嵌（文档中心/深链正常），外站嵌入依旧被拒，
  防点击劫持意图不变；CSP 未配置 frame-ancestors，不受影响
- 实测：响应头已返回 SAMEORIGIN；浏览器实测文档中心正文 iframe 完整渲染（官方样式/代码高亮正常）
- 交付：web 镜像重建滚动、镜像包重新导出（SHA256SUMS 同步更新）

## 修复 · root 探针无法 attach 非 root 用户的 Java 应用（本次）

- 线上现象：应用以指定用户启动、探针以 root systemd 运行，attach 必然失败
  （`Agent JAR not found or no Agent-Class attribute`）
- 根因（容器复现实证）：attach 握手 root 可通过（jcmd 可用），但 agent jar 由**目标 JVM 以其自身用户**打开——
  arthas-boot 缺省把发行包解压到运行者家目录（root 时为 `/root`，0700），非 root 目标进程读不到。
  权限方向与直觉相反：不是探针权限不够，而是目标进程读不到探针侧文件；重定向 HOME 无效（arthas 用 user.home）
- 修复（探针侧）：检测到 arthas-boot.jar 同目录存在 arthas-core.jar（完整发行目录）时，attach 显式
  `--arthas-home <发行目录>`——目录对目标用户可读即可跨用户 attach；同时离线内网免运行时下载
  （arthas-boot 缺省首次 attach 需从外网下载约 17MB，内网直接失败）
- 安装器：新增 `--user <应用用户>`（unit 写入 User= 并 chown 安装目录，同用户运行是最小权限的可靠口径）；
  `--arthas` 支持发行目录（交付包新布局 `probe/arthas/`），安装时自动 `chmod -R a+rX`；兼容旧单 jar 布局
- 探针镜像：内置完整发行目录 `/probe/arthas/`（entrypoint 指向其中 boot jar）；交付包 probe/ 同布局
- 服务端本机纳管通道同步同口径（LocalAsShExecutor 同样显式 --arthas-home）
- bq-agent 版本号 0.1.0 → 1.0.0（对齐"版本统一 1.0.0"，此前登记页显示旧版本号）
- 实测：容器内 nobody 运行 math-game、root 运行新探针注册平台 → 平台下发 `thread -n 3` →
  `success=true`、`channel=real`、真实线程栈回传；install.sh 六变体干跑（目录/单 jar/缺参/--user/无 arthas）
- 文档：deploy/README 探针边界重写（attach 与用户机制 + 两条出路）、根 README 已知边界、USAGE FAQ、
  交付清单与指南副本同步

## 整理 · 仓库清理与文档勘误（开源发布前，本次）

- 清理无效文件：根目录散落的运行日志（server-bq/vite-dev/vite-5175/llm-mock.log）与 web/ 下错位运行产物
  （`web/data/`、`web/llm-mock.log`、空的 `web/arthas-output/`）——均为 gitignore 运行时残留，不入库
- 文档勘误（内容与现状对齐）：
  - ACCEPTANCE.md：版本标记 V0.1.x → v1.0.0；手工启动示例的 jar 名 0.1.0-SNAPSHOT → 1.0.0；
    §2/§3 注明按演示模式核对（生产模式仅 admin、无拟真通道）；§14 已知边界与「生产去演示化」对齐
    （模拟靶场/演示账号仅存在于演示模式）
  - docs/USAGE.md：修复工作流 ② 步骤 1/2 之间被误插入的「显示名」段落（挪到「角色与权限」节，
    并补充生产模式账号种子说明）
  - deploy/README.md：环境变量表 AGENT_TOKEN / BIANQUE_DEMO_ENABLED / BIANQUE_ADMIN_PASSWORD 三行
    「默认/说明」两列错位修正；验证步骤注明演示/生产两种登录口径
  - 根 README.md：MySQL 备份命令改为容器内变量 `MYSQL_PASSWORD`（原写法在宿主机侧展开会取空密码）；
    目录树 design/plan 拆行
  - scripts/README.md：注明 smoke.sh 面向演示模式栈

## 修复 · 节点被删除后重新上线不再被服务端识别（本次）

- 根因：bq-agent 仅首次启动注册，此后心跳循环把一切失败（含服务端 410「节点未注册或已被清理」）当普通失败重试，
  导致被删除的节点重新上线后永远收 410、再也回不到节点列表
- agent 侧修复：心跳收到 410 时自动以**同一身份**（bq-agent.id 不变）重新注册并恢复心跳；
  其余失败仍按连续失败计数日志
- 语义说明：探针进程还在运行时删除节点，会在一个心跳内（约 5–15s）自动回归（身份稳定）；
  「删除离线节点」用于永久摘除——先停 bq-agent 再删
- 测试 44 → 45（新增「删除后心跳 410 → 重注册恢复同身份」集成用例）；
  端到端实测：注册上线 → 回拨离线 → 删除 → agent 日志「节点已被服务端清理（410），重新注册…」→ 12s 内同 ID 重新在线
- 交付物同步：server/probe 镜像与 dist 的 probe/bq-agent.jar 更新

## 修复 · docker 工作台仍显示旧通道卡（本次）

- 根因：工作台「执行通道」是前端改动，上一轮只重建了 server 镜像，web 镜像未重建，:80 仍是旧前端
- web 镜像重建滚动更新后 :80 实测显示「真实 3 · 拟真 0」
- 顺带加固 nginx 缓存策略：`/assets/*`（指纹资源）30 天长缓存，`index.html` no-cache——升级镜像后浏览器立即拿到新页面，不再出现残留旧界面

## 优化 · 探针裸机安装器参数化（本次）

- `install.sh`：`--jar/--arthas` 缺省改为**脚本同目录**的 bq-agent.jar / arthas-boot.jar（对齐交付包 probe/ 布局，
  不再依赖仓库相对路径）；新增 `--install-dir`（默认 /opt/bq-probe，完成提示动态取实际目录）；
  新增 `--java`（可给 JDK 根目录自动拼 bin/java，或 java 可执行文件绝对路径，缺省取 PATH，
  systemd ExecStart 使用解析后的绝对路径）
- 六种参数组合干跑验证（默认/目录型 JDK/文件型 JDK/缺 jar/缺 server/JDK 路径不存在）

## 优化 · 工作台执行通道卡改为真实路由分布（本次）

- 原「执行通道」显示 yml 静态配置 `bianque.execution.mode`（默认 mock），与按实例真实路由脱节（生产挂真实探针仍显示「演示模拟」）
- 改为按 `DiagService.route` 同口径统计：探针(agent-*)/本机纳管=真实、靶场种子=拟真，overview 返回 `channels:{real,mock}`
- 移除失去消费者的 `bianque.execution.mode` 配置；实测容器栈显示「真实 3 · 拟真 0」

## 优化 · 推理参数映射内置说明收进 tooltip（本次）

- 「清空（用内置映射）」旁的内置映射说明由内联长文本改为信息图标 tooltip（三行：非 off 形态 / 省略采样参数 / off 形态）

## 优化 · 管理设置表格布局 / 登录页演示账号按模式显隐（本次）

- **管理设置**：权限矩阵表（命令族 240 / 操作员 150）与用户与角色表（账号 120 / 角色 140 / 操作 310）
  文本列全部不换行（`white-space: nowrap`），权限点列保持标签流式；安全基线「高危黑名单」等标签列同步不换行
- **登录页**：`/api/health` 新增 `demo` 标志（公开端点）；生产模式（`BIANQUE_DEMO_ENABLED=false`）
  自动隐藏「演示账号（点击填充）」区块，演示模式照常显示
- 实测：demo-on 登录页显示三个演示账号、demo-off 登录页无演示区块；两表单行渲染截图确认；
  容器栈滚动更新后 health 带 `"demo":false`、无令牌注册仍 401

## 安全 · 后台接口鉴权审计与加固（本次）

- **审计范围**：全部 Controller × 守卫矩阵（登录墙 / diag:execute / admin:user / X-Agent-Token / X-Alert-Token）逐一核对并实测
- **修复①**：`/actuator/**` 不在 `/api/**` 鉴权墙内且暴露 metrics/env —— 收敛为仅 health（show-details: never）
- **修复②**：生产模式（demo-enabled=false）节点令牌未配置时 `/api/agent/**` 完全开放（实测可注册伪造节点）——
  新增 `SecurityGuard` fail-fast：AGENT_TOKEN 缺失拒绝启动；`/api/agent/download` 一并纳入令牌校验
- **修复③**：口令存储 sha256(salt+pw) → **BCrypt**（spring-security-crypto，仅 crypto 模块无过滤器链）；
  历史 sha256 行登录校验通过后透明升级（老库零迁移）
- **修复④**：web nginx 增加安全响应头：CSP（default-src 'self' 等）/ X-Frame-Options DENY /
  X-Content-Type-Options nosniff / Referrer-Policy no-referrer
- 审计确认无问题项：告警 ingest 未配 token 即关闭（403 安全默认）、登录失败信息不区分用户存在性、
  LLM key/Agent token 掩码或仅 admin 可见、/api/admin/** 拦截器 admin:user 门禁、CORS 未开放（同源）、
  H2 console 关闭、无自查册/无文件上传、诊断命令黑名单全通道生效
- 已知边界（记录不阻塞）：登录无速率限制（内网部署语义）；审计/LLM 配置(掩码)对登录用户只读属产品透明化设计
- 测试 40 → 44（SecurityGuard 门禁 ×3、历史 sha256 透明升级 ×1）；实测：假节点 401、download 401/200、
  actuator metrics 404、CSP 头生效、admin 行升级为 $2a$（60 位）
- 部署注意：生产模式 `.env` 必配 `AGENT_TOKEN`（与服务端一致），否则拒绝启动

## 功能 · 生产去演示化 / 显示名与用户删除 / AI 流式与思考强度（本次）

- **生产模式**（`BIANQUE_DEMO_ENABLED=false`，prod compose 默认）：不注入模拟靶场实例/种子告警、
  不创建演示账号 ops/viewer，拟真通道不装配；admin 由 `BIANQUE_ADMIN_PASSWORD` 引导
  （仅 admin 不存在时生效一次，缺省拒绝启动防锁死）；历史演示账号在「管理设置」可删除
- **显示名**：侧边栏点击显示名自助修改（`PUT /api/auth/profile`，登录态即时刷新）；
  管理员可对任意账号改名（`POST /api/admin/users/{u}/display-name`）；
  新增用户删除（内置 admin 与当前登录账号保护）
- **AI 问诊流式**：新增 `POST /api/llm/chat/stream`（SSE）——思考过程（reasoning_content）与
  回答增量实时下发，`X-Accel-Buffering: no` 防反代缓冲；前端 fetch 流式渲染，
  思考面板自动展开/收起，done 事件汇总 tokens/refs
- **会话级思考强度**：提问框下方 ZCode 式 默认/关/低/中/高 快捷切换（本地记忆），
  请求级覆盖接入配置默认档；非法等级 400
- **推理参数映射**：接入配置新增 reasoningParams（JSON）——按思考强度深度覆盖请求体参数
  （如 `{"off":{"thinking":{"type":"disabled"}}}`），适配非 OpenAI 风格推理开关；
  保存时校验，未映射等级回退内置形态
- 测试 34 → 40（请求级思考强度/参数映射/SSE 流式/显示名与删除/生产模式隔离），全绿
- 杂项：vite 代理目标支持 `BQ_API_TARGET` 覆盖；dev-llm-mock 支持 stream=true SSE 演示


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
  （应用全名 / 实例 ID + 通道 · 主机 · pid · 离线标记）；卡片下方新增「当前：」完整标识行（悬停看全文）。
  选项条目取消固定 34px 高改为自适应 + 上下留白（popper 挂在 body 下，改用 popper-class 全局样式）
- **发布配置一致性修复（修「数据库连不上」隐患）**：compose 的 server 服务此前未注入
  `BIANQUE_DB_PASSWORD`，服务端 `application-docker.yml` 的 `${BIANQUE_DB_PASSWORD:bianque123}`
  永远落到默认值——一旦按 .env.example 指引修改数据库密码，MySQL 密码已变而服务端仍用 bianque123，
  连接必失败；现由 compose 同时注入 MySQL 与服务端，两侧自动一致。同批修复：
  ① `GET /api/agent/download` 容器内 404（镜像未带 bq-agent.jar，节点接入指引第一步即失败）——
  bq-agent.jar 随 server 镜像分发（/app/agents/）；② `PROBE_INTERVAL` 补充 compose 透传与 .env 文档；
  ③ .env.example 补充「MySQL 密码仅首次初始化生效，改密码用 ALTER USER」说明。
  审计项：dev compose（docker-compose.yml）变量名同源一致、Redis 双方均无密码一致、
  JDBC URL 服务名/库名/时区与 compose 服务定义一致、healthcheck mysqladmin ping 语义正确

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
