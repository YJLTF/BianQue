# deploy（部署）

当前内容：
- `docker-compose.yml` — MySQL 8.4 + Redis 7.4（`docker compose up -d mysql redis`；演示账号 bianque/bianque123，仅限内网）
- `docs-site/` — 文档中心静态产物（docs-center 管线输出，可由 Nginx / 平台静态托管）

规划中：Nginx 配置、离线部署包（镜像 tar + agent 安装器）、节点/服务 systemd 与 as-service.bat 安装脚本。
