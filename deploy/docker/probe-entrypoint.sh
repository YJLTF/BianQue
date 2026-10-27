#!/usr/bin/env bash
# 探针容器入口：拼装启动参数并前台运行 bq-agent
set -eu
ARGS="--server ${SERVER_URL:?缺少 SERVER_URL（平台地址，如 http://平台IP:8080）}"
[ -n "${AGENT_TOKEN:-}" ] && ARGS="$ARGS --token $AGENT_TOKEN"
[ -n "${PROBE_NAME:-}" ] && ARGS="$ARGS --name $PROBE_NAME"
ARGS="$ARGS --arthas /probe/arthas-boot.jar --interval ${PROBE_INTERVAL:-5}"
exec java -jar /probe/bq-agent.jar $ARGS
