#!/usr/bin/env bash
# ============================================================
# 扁鹊节点探针（bq-agent）裸机安装器（Linux + systemd）
# 前置：目标主机已装 JDK 11+（java 在 PATH），root 或 sudo 权限
# 用法:
#   sudo ./install.sh --server http://平台IP:8080 [--token <bianque.agent.token>] \
#        [--jar /path/bq-agent.jar] [--arthas /path/arthas-boot.jar] [--name 节点名]
# 卸载: sudo systemctl disable --now bq-probe && sudo rm -rf /opt/bq-probe
# ============================================================
set -euo pipefail

SERVER=""; TOKEN=""; JAR=""; ARTHAS=""; NAME=""
INSTALL_DIR=/opt/bq-probe
while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2;;
    --token)  TOKEN="$2"; shift 2;;
    --jar)    JAR="$2"; shift 2;;
    --arthas) ARTHAS="$2"; shift 2;;
    --name)   NAME="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done
[ -z "$SERVER" ] && { echo "缺少 --server（平台地址）"; exit 2; }

# 默认就近取同目录产物（仓库构建后：server/bq-agent/target/bq-agent.jar + docs/research arthas-boot.jar）
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${JAR:-$DIR/../../server/bq-agent/target/bq-agent.jar}"
ARTHAS="${ARTHAS:-$DIR/../../docs/research/arthas-inspect/arthas-boot.jar}"
[ -f "$JAR" ] || { echo "找不到 bq-agent.jar：$JAR（先 mvn -B package -DskipTests 或用 --jar 指定）"; exit 2; }
[ -f "$ARTHAS" ] || { echo "找不到 arthas-boot.jar：$ARTHAS（用 --arthas 指定）"; exit 2; }
command -v java >/dev/null || { echo "目标主机需要 JDK 11+（java 不在 PATH）"; exit 2; }

mkdir -p "$INSTALL_DIR"
install -m 0644 "$JAR" "$INSTALL_DIR/bq-agent.jar"
install -m 0644 "$ARTHAS" "$INSTALL_DIR/arthas-boot.jar"

cat > /etc/systemd/system/bq-probe.service <<EOF
[Unit]
Description=BianQue Platform Probe (bq-agent)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/java -jar ${INSTALL_DIR}/bq-agent.jar --server ${SERVER} ${TOKEN:+--token ${TOKEN}} ${NAME:+--name ${NAME}} --arthas ${INSTALL_DIR}/arthas-boot.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now bq-probe
sleep 3
systemctl --no-pager -l status bq-probe | head -8
echo
echo "安装完成：/opt/bq-probe  ·  查看日志: journalctl -u bq-probe -f"
echo "到平台「节点管理」页确认该节点已上线（心跳 5s）"
