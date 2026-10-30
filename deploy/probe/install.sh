#!/usr/bin/env bash
# ============================================================
# 扁鹊节点探针（bq-agent）裸机安装器（Linux + systemd）
# 前置：目标主机已装 JDK 11+（java 在 PATH，或用 --java 指定），root 或 sudo 权限
# 用法:
#   sudo ./install.sh --server http://平台IP:8080 [--token <bianque.agent.token>] \
#        [--jar /path/bq-agent.jar] [--arthas /path/发行目录或arthas-boot.jar] [--name 节点名] \
#        [--install-dir /opt/bq-probe] [--java /opt/jdk21 | /opt/jdk21/bin/java] \
#        [--user <应用用户>]
#   --jar/--arthas 缺省取脚本同目录的 bq-agent.jar / arthas/（完整发行目录，推荐：离线可用、
#     对目标 JVM 用户可读，root 探针也能 attach 其它用户的应用）；也兼容仅 arthas-boot.jar
#     （旧布局，首次 attach 需联网由 arthas-boot 自行下载发行）
#   --java 可给 JDK 根目录（自动拼 bin/java）或 java 可执行文件绝对路径；缺省取 PATH
#   --user 服务运行账号：与目标 JVM 同用户运行是 attach 的可靠口径（可 attach 该用户启动的 JVM，
#     并满足最小权限）；缺省 root（配合发行目录仍可 attach 其它用户的 JVM）
#   同机仅允许一个探针：安装器会先停旧 bq-probe 服务，若仍检测到手工启动的 bq-agent 进程则拒绝安装
# 卸载: sudo systemctl disable --now bq-probe && sudo rm -rf <INSTALL_DIR>
# ============================================================
set -euo pipefail

SERVER=""; TOKEN=""; JAR=""; ARTHAS=""; NAME=""; JAVA_BIN=""; USER_ACCT=""
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
    --user)   USER_ACCT="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done
[ -z "$SERVER" ] && { echo "缺少 --server（平台地址）"; exit 2; }
if [ -n "$USER_ACCT" ]; then
  id "$USER_ACCT" >/dev/null 2>&1 || { echo "用户不存在：$USER_ACCT"; exit 2; }
fi

# 升级/重装路径：先停已有服务实例；再防双探针——残留的手工探针进程会造成重复节点与命令争抢
systemctl stop bq-probe 2>/dev/null || true
if pgrep -f "bq-agent.jar" >/dev/null 2>&1; then
  echo "检测到本机仍有运行中的 bq-agent 进程（同机只应一个探针）："
  pgrep -af "bq-agent.jar" | sed 's/^/  /'
  echo "请先 kill 旧进程（如为手工 java -jar 起的，机器重启也不会自启）后重试安装。"
  exit 1
fi

# 产物就近取脚本同目录（交付包 probe/ 布局）；--jar/--arthas 可覆盖为任意路径
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${JAR:-$DIR/bq-agent.jar}"
[ -f "$JAR" ] || { echo "找不到 bq-agent.jar：$JAR（与脚本同目录放置，或用 --jar 指定）"; exit 2; }

# arthas 解析：优先完整发行目录（含 arthas-core.jar）；兼容单个 arthas-boot.jar（旧布局）
if [ -z "$ARTHAS" ]; then
  if [ -d "$DIR/arthas" ] && [ -f "$DIR/arthas/arthas-boot.jar" ]; then
    ARTHAS="$DIR/arthas"
  elif [ -f "$DIR/arthas-boot.jar" ]; then
    ARTHAS="$DIR/arthas-boot.jar"
  else
    echo "找不到 arthas：脚本同目录应有 arthas/ 发行目录（或 arthas-boot.jar），或用 --arthas 指定"
    exit 2
  fi
fi
if [ -d "$ARTHAS" ]; then
  [ -f "$ARTHAS/arthas-boot.jar" ] || { echo "arthas 目录缺 arthas-boot.jar：$ARTHAS"; exit 2; }
  # 完整发行目录必须含 arthas-core.jar：缺失时 arthas-boot 首次 attach 需联网下载发行包，内网直接失败
  [ -f "$ARTHAS/arthas-core.jar" ] || { echo "arthas 目录缺 arthas-core.jar（非完整发行目录，离线内网 attach 会全部失败）：$ARTHAS"; exit 2; }
  ARTHAS_KIND=dir
else
  [ -f "$ARTHAS" ] || { echo "找不到 arthas-boot.jar：$ARTHAS"; exit 2; }
  ARTHAS_KIND=jar
fi

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
if [ "$ARTHAS_KIND" = dir ]; then
  mkdir -p "$INSTALL_DIR/arthas"
  cp -a "$ARTHAS/." "$INSTALL_DIR/arthas/"
  # 关键：agent jar 由目标 JVM 以其自身用户打开，发行目录必须对目标用户可读
  # （root 探针 attach 非 root 应用时，不可读目录会让 attach 报 Agent JAR not found）
  chmod -R a+rX "$INSTALL_DIR/arthas"
  BOOT_JAR="$INSTALL_DIR/arthas/arthas-boot.jar"
else
  install -m 0644 "$ARTHAS" "$INSTALL_DIR/arthas-boot.jar"
  BOOT_JAR="$INSTALL_DIR/arthas-boot.jar"
fi
if [ -n "$USER_ACCT" ]; then
  chown -R "$USER_ACCT" "$INSTALL_DIR"
fi

cat > /etc/systemd/system/bq-probe.service <<EOF
[Unit]
Description=BianQue Platform Probe (bq-agent)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
${USER_ACCT:+User=${USER_ACCT}}
ExecStart=${JAVA_BIN} -jar ${INSTALL_DIR}/bq-agent.jar --server ${SERVER} ${TOKEN:+--token ${TOKEN}} ${NAME:+--name ${NAME}} --arthas ${BOOT_JAR}
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
if [ -n "$USER_ACCT" ]; then
  echo "运行账号：${USER_ACCT}（可 attach 该用户启动的 JVM；其它用户的 JVM 需另装对应用户的探针）"
fi
echo "到平台「节点管理」页确认该节点已上线（心跳 5s）"
