#!/bin/sh
# 服务端容器入口：jar 由 compose 从 deploy/artifacts 目录挂入（纯运行环境镜像，产物不进镜像）
# 升级 = 替换宿主 deploy/artifacts/bq-server.jar + docker compose restart，无需重建镜像
set -eu
JAR="/app/artifacts/bq-server.jar"
if [ ! -f "$JAR" ]; then
    echo "[bianque-server] 缺少产物 $JAR" >&2
    echo "  请将宿主构建的服务端包放入 deploy/artifacts/bq-server.jar 并挂载该目录：" >&2
    echo "    cp server/bq-server/target/bq-server-*.jar deploy/artifacts/bq-server.jar" >&2
    echo "  详见 deploy/README「构建发布」" >&2
    exit 1
fi
# 透传 compose command 追加参数（--bianque.* 覆盖项）
exec java -XX:MaxRAMPercentage=75 -jar "$JAR" "$@"
