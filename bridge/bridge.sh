#!/usr/bin/env bash
# DeviceHud 有线旁路 Shell 桥 —— 电脑侧控制脚本
#
# 原理：用 adb 以 app_process 起一个 uid=shell(2000) 的常驻 Java 进程，
#      它监听 127.0.0.1:PORT，接受单行 shell 命令。
#      应用侧连回环端口即可读到 /proc/stat 等 untrusted_app 读不到的数据，
#      完全不依赖 Shizuku / root。
#
# 鉴权：每次启动生成一次性 token，写入
#      设备 /data/local/tmp/devicehud_bridge.token（600，应用读不到）
#      与应用外部目录（应用读得到），桥只接受携带该 token 且 uid 匹配的连接。
#
# 用法：
#   ./bridge.sh start    编译（首次或源码变动）+ 推送 + 启动
#   ./bridge.sh build    只编译
#   ./bridge.sh stop     停掉设备上的桥
#   ./bridge.sh status   查看状态并自测
#   ./bridge.sh log      看桥日志
#   ./bridge.sh token    打印当前 token
#
# 环境变量：ANDROID_HOME（默认 /d/androidSDK）、BRIDGE_PORT（默认 47200）

set -u

# adb 的远端路径不能被 MSYS 改写成 Windows 路径
export MSYS_NO_PATHCONV=1

PORT="${BRIDGE_PORT:-47200}"
# 正式包与内测包（.debug 后缀，由 applicationIdSuffix 派生）可同时安装在一个设备上
PKG_BASE="io.github.zuoyexingchen.devicehud"
PKG="$PKG_BASE"
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/d/androidSDK}"
ADB="$SDK/platform-tools/adb.exe"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
JAVAC="${JAVAC:-javac}"
D8="$BUILD_TOOLS/d8.bat"

DEX_LOCAL="$HERE/out/classes.dex"
TOKEN_LOCAL="$HERE/out/token.txt"
REMOTE_TOKEN="/data/local/tmp/devicehud_bridge.token"
REMOTE_DEX="/data/local/tmp/devicehud_bridge.dex"
REMOTE_LOG="/data/local/tmp/devicehud_bridge.log"
APP_TOKEN="/sdcard/Android/data/$PKG/files/bridge.token"
MAIN_CLASS="io.github.zuoyexingchen.devicehud.bridge.ShellBridge"

die() { echo "!! $*" >&2; exit 1; }

# 把 MSYS 路径转成 Windows 路径给 adb 当本地参数
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

# 正式版优先，没装则回落到内测版
resolve_pkg() {
  local p
  for p in "$PKG_BASE" "$PKG_BASE.debug"; do
    if "$ADB" shell "pm list packages $p 2>/dev/null" \
        | tr -d '\r' | grep -qx "package:$p"; then
      printf '%s' "$p"; return 0
    fi
  done
  printf '%s' "$PKG_BASE"
}

app_uid() {
  # 用 -F 精确匹配：否则 devicehud 会把 devicehud.debug 一起捞出来
  "$ADB" shell "cmd package list packages -U 2>/dev/null | grep -F 'package:$PKG '" \
    | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1
}

# 令牌由应用生成（应用内部目录），电脑侧只负责读取
device_token() {
  local t=""
  t="$("$ADB" shell "cat /sdcard/Android/data/$PKG/files/bridge.token 2>/dev/null" \
        | tr -d '\r\n')"
  if [ "${#t}" -lt 16 ]; then
    t="$("$ADB" shell "run-as $PKG cat /data/data/$PKG/files/bridge.token 2>/dev/null" \
          | tr -d '\r\n')"
  fi
  printf '%s' "$t"
}

cmd_build() {
  [ -n "${BUILD_TOOLS:-}" ] || die "找不到 build-tools"
  [ -x "$ADB" ] || die "找不到 adb: $ADB"

  # d8.bat 是 Windows 批处理，JAVA_HOME 必须是 Windows 路径
  if [ -n "${JAVA_HOME:-}" ]; then
    case "$JAVA_HOME" in
      /*) JAVA_HOME="$(cygpath -w "$JAVA_HOME")"; export JAVA_HOME ;;
    esac
  fi

  # javac / d8 是 Windows 程序，不认 MSYS 的 /d/... 路径，必须在 bridge 目录下用相对路径
  cd "$HERE" || die "进不去 $HERE"
  rm -rf out/classes out/classes.dex
  mkdir -p out/classes
  find src -name '*.java' > out/sources.txt

  echo "== javac =="
  if ! "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
      -d out/classes @out/sources.txt > out/javac.log 2>&1; then
    cat out/javac.log
    die "javac 失败"
  fi

  echo "== d8 =="
  if ! "$D8" --min-api 26 --output out $(find out/classes -name '*.class') \
      > out/d8.log 2>&1; then
    cat out/d8.log
    die "d8 失败"
  fi

  [ -f out/classes.dex ] || { cat out/d8.log; die "没有产出 classes.dex"; }
  echo "ok: out/classes.dex ($(stat -c %s out/classes.dex) bytes)"
}

cmd_start() {
  [ -f "$DEX_LOCAL" ] || cmd_build
  [ -x "$ADB" ] || die "找不到 adb: $ADB"
  PKG="$(resolve_pkg)"
  echo "== 目标应用：$PKG =="

  local token uid
  token="$(device_token)"
  [ "${#token}" -ge 16 ] || die "读不到令牌：先在手机上打开一次 DeviceHud（应用启动时会生成）"
  mkdir -p "$HERE/out"
  printf '%s' "$token" > "$TOKEN_LOCAL"

  uid="$(app_uid)"
  [ -n "$uid" ] || echo "warn: 没查到 $PKG 的 uid，将跳过 uid 校验（仍要求 token）"

  echo "== 配置桥的令牌 =="
  # 桥自己的一份（600，应用读不到，也不进 cmdline）
  printf '%s' "$token" | "$ADB" shell "cat > $REMOTE_TOKEN && chmod 600 $REMOTE_TOKEN"

  echo "== 推送 dex =="
  "$ADB" push "$(winpath "$DEX_LOCAL")" "$REMOTE_DEX" >/dev/null 2>&1 || die "push 失败"

  echo "== 停旧进程 =="
  "$ADB" shell "pkill -f devicehud_bridge" >/dev/null 2>&1
  sleep 0.4

  echo "== 启动 =="
  "$ADB" shell "setsid nohup app_process \
      -Djava.class.path=$REMOTE_DEX \
      /system/bin --nice-name=devicehud_bridge \
      $MAIN_CLASS $PORT $REMOTE_TOKEN ${uid:-0} $PKG > $REMOTE_LOG 2>&1 < /dev/null &" \
      >/dev/null 2>&1

  for i in $(seq 1 30); do
    sleep 0.5
    if "$ADB" shell "grep -q BRIDGE_READY $REMOTE_LOG 2>/dev/null" >/dev/null 2>&1; then
      echo "ok: $("$ADB" shell "grep -m1 BRIDGE_READY $REMOTE_LOG" | tr -d '\r')"
      cmd_status
      return 0
    fi
  done

  echo "-- 启动日志 --"
  "$ADB" shell "cat $REMOTE_LOG 2>/dev/null" | tail -20
  die "桥没起来"
}

cmd_stop() {
  "$ADB" shell "pkill -f devicehud_bridge" >/dev/null 2>&1
  echo "stopped"
}

cmd_status() {
  local token py
  PKG="$(resolve_pkg)"
  token="$(tr -d '\r\n' < "$TOKEN_LOCAL" 2>/dev/null || true)"
  py="$(command -v python3 || command -v python || true)"

  echo "-- 进程 --"
  "$ADB" shell "ps -A -o PID,USER,NAME | grep devicehud_bridge | grep -v grep" | tr -d '\r'

  echo "-- 负例：不带 token 直接发命令，应被拒 --"
  "$ADB" shell "printf '__PING__\n' | nc -w 2 127.0.0.1 $PORT 2>&1 | head -2" | tr -d '\r'

  [ -n "$token" ] || { echo "(无本地 token，跳过握手自测)"; return 0; }
  [ -n "$py" ] || { echo "(本机没有 python，跳过握手自测)"; return 0; }

  echo "-- 正例：经 adb forward 走完整挑战应答 --"
  "$ADB" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1
  "$py" "$(winpath "$HERE/probe.py")" "$PORT" "$token" "__PING__"
  "$py" "$(winpath "$HERE/probe.py")" "$PORT" "$token" "id -u; getprop ro.product.model" | sed 's/^/  /'
  echo "-- 正例：/proc/stat 前两行 --"
  "$py" "$(winpath "$HERE/probe.py")" "$PORT" "$token" "__STAT__" | head -4 | sed 's/^/  /'
  echo "-- 负例：错误 token 应被拒 --"
  "$py" "$(winpath "$HERE/probe.py")" "$PORT" "wrong-token-wrong-token" "__PING__" | sed 's/^/  /'
  "$ADB" forward --remove "tcp:$PORT" >/dev/null 2>&1
}

cmd_log()    { "$ADB" shell "tail -40 $REMOTE_LOG 2>/dev/null" | tr -d '\r'; }
cmd_token()  { device_token; echo; }

case "${1:-start}" in
  build)  cmd_build ;;
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  log)    cmd_log ;;
  token)  cmd_token ;;
  *)      echo "用法: $0 {start|build|stop|status|log|token}"; exit 1 ;;
esac
