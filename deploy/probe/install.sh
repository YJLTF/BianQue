#!/usr/bin/env bash
# ============================================================
# 扁鹊节点探针（bq-agent）裸机安装器（Linux + systemd）
# 前置：目标主机已装 JDK 11+（java 在 PATH，或用 --java 指定），root 或 sudo 权限
# 用法:
#   sudo ./install.sh --server http://平台IP:8080 [--token <bianque.agent.token>] \
#        [--jar /path/bq-agent.jar] [--arthas /path/arthas-boot.jar] [--name 节点名] \
#        [--install-dir /opt/bq-probe] [--java /opt/jdk21 | /opt/jdk21/bin/java]
#   --jar/--arthas 缺省取脚本同目录的 bq-agent.jar / arthas-boot.jar（与交付包 probe/ 布局一致）
#   --java 可给 JDK 根目录（自动拼 bin/java）或 java 可执行文件绝对路径；缺省取 PATH
# 卸载: sudo systemctl disable --now bq-probe && sudo rm -rf <INSTALL_DIR>
# ============================================================
set -euo pipefail

SERVER=""; TOKEN=""; JAR=""; ARTHAS=""; NAME=""; JAVA_BIN=""
INSTALL_DIR=/opt/bq-probe
while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2;;
    --token)  TOKEN="$2"; shift 2;;
    --jar)    JAR="$2"; shift 2;;
    --arthas) ARTHAS="$2"; shift 2;;
    --name)   NAME="$2"; shift 2;;
    --install-dir) INSTALL_DIR="$2"; shift 2;;
    --java)   JAVA_BIN="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done
[ -z "$SERVER" ] && { echo "缺少 --server（平台地址）"; exit 2; }

# 产物就近取脚本同目录（交付包 probe/ 布局）；--jar/--arthas 可覆盖为任意路径
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${JAR:-$DIR/bq-agent.jar}"
ARTHAS="${ARTHAS:-$DIR/arthas-boot.jar}"
[ -f "$JAR" ] || { echo "找不到 bq-agent.jar：$JAR（与脚本同目录放置，或用 --jar 指定）"; exit 2; }
[ -f "$ARTHAS" ] || { echo "找不到 arthas-boot.jar：$ARTHAS（与脚本同目录放置，或用 --arthas 指定）"; exit 2; }

# JDK 解析：--java 给 JDK 根目录则拼 bin/java，给可执行文件则直接用；缺省取 PATH
if [ -n "$JAVA_BIN" ]; then
  if [ -d "$JAVA_BIN" ]; then
    JAVA_BIN="$JAVA_BIN/bin/java"
  fi
  [ -x "$JAVA_BIN" ] || { echo "指定的 JDK 不可执行：$JAVA_BIN"; exit 2; }
else
  command -v java >/dev/null || { echo "目标主机需要 JDK 11+（java 不在 PATH，可用 --java 指定）"; exit 2; }
  JAVA_BIN="$(command -v java)"
fi

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
ExecStart=${JAVA_BIN} -jar ${INSTALL_DIR}/bq-agent.jar --server ${SERVER} ${TOKEN:+--token ${TOKEN}} ${NAME:+--name ${NAME}} --arthas ${INSTALL_DIR}/arthas-boot.jar
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
echo "安装完成：${INSTALL_DIR}  ·  查看日志: journalctl -u bq-probe -f"
echo "到平台「节点管理」页确认该节点已上线（心跳 5s）"
