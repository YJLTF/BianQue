#!/usr/bin/env bash
# 探针容器入口：拼装启动参数并前台运行 bq-agent（纯运行环境镜像，探针包经 volumes 挂载）
# 升级 = 替换宿主 artifacts/bq-agent.jar + 重启容器，无需重建镜像
set -eu
JAR="/probe/artifacts/bq-agent.jar"
if [ ! -f "$JAR" ]; then
    echo "[bianque-probe] 缺少探针包 $JAR" >&2
    echo "  请挂载宿主产物目录：-v <部署目录>/artifacts:/probe/artifacts" >&2
    echo "  并将 bq-agent.jar 放入该目录，详见 deploy/README「构建发布」" >&2
    exit 1
fi
ARGS="--server ${SERVER_URL:?缺少 SERVER_URL（平台地址，如 http://平台IP:8080）}"
[ -n "${AGENT_TOKEN:-}" ] && ARGS="$ARGS --token $AGENT_TOKEN"
[ -n "${PROBE_NAME:-}" ] && ARGS="$ARGS --name $PROBE_NAME"
ARGS="$ARGS --arthas /probe/arthas/arthas-boot.jar --interval ${PROBE_INTERVAL:-5}"
exec java -jar "$JAR" $ARGS
