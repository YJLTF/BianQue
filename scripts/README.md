# scripts（脚本与工具）

| 脚本 | 用途 | 用法 |
|---|---|---|
| `start-all.bat` | Windows 一键全栈：MySQL/Redis（Docker）→ 构建三模块 → 后端 → 本机演示节点 bq-agent → 前端 | `scripts\start-all.bat`（可选环境变量 `JAVA_HOME` / `INGEST_TOKEN` / `AGENT_TOKEN`） |
| `smoke.sh` | 冒烟验收：探活/认证/RBAC/拟真执行/高危拦截/哈希链/巡检/文档中心（含 `INGEST_TOKEN` 告警接入项）。**面向演示模式栈**（依赖 pod-3 模拟实例与演示账号，生产模式栈不适用） | `bash scripts/smoke.sh [base-url] [ingest-token]` |
| `dev-llm-mock.py` | 假 OpenAI Chat Completions 端点（内网演示 AI 问诊 / E2E 用，含 reasoning_content） | `python scripts/dev-llm-mock.py [port=8901]`，端点 `http://127.0.0.1:8901/v1/chat/completions` |

诊断靶场：`docs/research/arthas-inspect/math-game.jar`（materials 发行包自带演示程序）。
启动后即被 bq-agent 心跳发现，可在「节点管理」/「诊断台」直接对它执行真实 Arthas 命令：

```bash
java -jar docs/research/arthas-inspect/math-game.jar
```

验收流程（启动 → 冒烟 → 人工核对）见 [ACCEPTANCE.md](../ACCEPTANCE.md)。
