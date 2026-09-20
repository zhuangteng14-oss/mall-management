#!/usr/bin/env bash
#
# A3 并发测试 —— 启动封装。
#
# 存在的两个理由，都不是「图方便」：
#
# 1) 必须两步编译，不能用单文件源码模式。
#    单文件源码模式（java Foo.java）由启动器在运行期就地编译，此时不使用任何编译期
#    -encoding，而按宿主平台默认字符集读源文件。Windows 上 file.encoding=GBK 时，
#    源里所有中文串失真，依赖中文响应体的断言（msg 比对、「数据库错误」前缀等）全部
#    静默落空 —— 同一脚本、同一机器，只因启动方式不同，结论相反且不报任何错。
#    实测：源码模式 + GBK 下 S3 由「异常单=0 / PASS」变成「异常单=10 / FAIL」。
#    故这里用 javac -encoding UTF-8 把编码在编译期钉死，运行期字符集只影响终端显示，
#    不影响判定。运行器另有自检，谁若改用源码模式，会被 exit 2 挡下。
#
# 2) 驱动 jar 的解析要有回退，且不得静默选错。
#    首选 D3 定稿的单条路径；找不到再走 dependency:build-classpath 解析，
#    并校验「非 -sources、major=8」。选不到就报错退出，不猜。
#
# 用法:
#   scripts/concurrency/run.sh --jdbc-url <url> --expect-db-host <host> [其它 A3 选项...]
#   scripts/concurrency/run.sh --help            # 透传给运行器
#
# 环境变量:
#   A3_DRIVER_JAR  直接指定 mysql-connector jar（跳过解析）
#   A3_OUT_DIR     编译产物目录，默认 <repo>/.a3-out
#   A3_LOG_DIR     日志目录，默认 <repo>/scripts/concurrency/reports
#
set -u

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$REPO/scripts/concurrency/ConcurrencyRunner.java"
OUT="${A3_OUT_DIR:-$REPO/.a3-out}"
LOG_DIR="${A3_LOG_DIR:-$REPO/scripts/concurrency/reports}"
MVNW="$REPO/Mall-management-server/mvnw"
POM="$REPO/Mall-management-server/pom.xml"

die() {
  echo "run.sh: $*" >&2
  exit 2
}

# ---------- 平台形态 ----------
# Git Bash 给的绝对路径是 /c/Users/...，交给 Windows 的 javac/java 会变成 \c\Users\...
# 而被当成「找不到文件」。故交给原生程序前一律转成 C:/Users/... 形态；
# classpath 分隔符同样分平台（Windows 用 ;，POSIX 用 :）。
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

winpath() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

# ---------- 工具链 ----------
command -v javac >/dev/null 2>&1 || die "找不到 javac（需要 JDK 17+，不是 JRE）"
command -v java  >/dev/null 2>&1 || die "找不到 java"

jver="$(javac -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
[ -n "$jver" ] || die "无法解析 javac 版本"
[ "$jver" -ge 17 ] 2>/dev/null || die "需要 JDK 17+，当前 javac 主版本=$jver（本项目 baseline 为 Temurin 17）"

[ -f "$SRC" ] || die "找不到源文件 $SRC"

# ---------- 驱动 jar ----------
# 校验：文件在、不是 sources/javadoc、文件名里的 major 为 8。
ok_driver() {
  [ -f "$1" ] || return 1
  case "$(basename "$1")" in
    *-sources.jar|*-javadoc.jar) return 1 ;;
  esac
  case "$(basename "$1")" in
    mysql-connector-j-8.*|mysql-connector-java-8.*) return 0 ;;
  esac
  return 1
}

DRIVER="${A3_DRIVER_JAR:-}"
if [ -n "$DRIVER" ]; then
  ok_driver "$DRIVER" || die "A3_DRIVER_JAR=$DRIVER 不是可用的 mysql-connector 8.x jar"
else
  # 首选：D3 定稿的单条路径（不 glob —— glob 会撞上 -sources.jar 与旧版 8.0.28）
  D3="$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar"
  if ok_driver "$D3"; then
    DRIVER="$D3"
    DRIVER_FROM="D3 定稿路径"
  else
    # 回退：用 Maven 解析依赖树，取那条 mysql 驱动
    [ -x "$MVNW" ] || [ -f "$MVNW" ] || die "回退需要一个 Maven 工程：找不到 $MVNW"
    [ -f "$POM" ] || die "找不到 $POM"
    CPFILE="$OUT/a3-classpath.txt"
    mkdir -p "$OUT"
    echo "run.sh: D3 定稿路径无驱动，回退 dependency:build-classpath 解析…" >&2
    ( cd "$REPO/Mall-management-server" && sh ./mvnw -q -B dependency:build-classpath \
        -Dmdep.outputFile="$CPFILE" -DincludeScope=runtime ) >&2 \
      || die "dependency:build-classpath 失败（首次需要网络拉取依赖）"
    [ -f "$CPFILE" ] || die "build-classpath 未产出 $CPFILE"
    # 取那一条驱动；有多条就报错，不静默挑第一条
    matches="$(tr ':' '\n' < "$CPFILE" | grep -E 'mysql-connector-j(a)?-[0-9]' | grep -v -E -- '-sources\.jar|-javadoc\.jar' || true)"
    cnt="$(printf '%s\n' "$matches" | grep -c . || true)"
    [ "$cnt" -eq 1 ] || die "回退解析出 $cnt 条 mysql 驱动（要求恰好 1 条）：$(printf '%s ' $matches)"
    ok_driver "$matches" || die "回退解析到的驱动不合格: $matches"
    DRIVER="$matches"
    DRIVER_FROM="dependency:build-classpath 回退"
  fi
fi

# ---------- 编译（编码在编译期钉死）----------
mkdir -p "$OUT" "$LOG_DIR" || die "无法创建 $OUT / $LOG_DIR"

SRC_W="$(winpath "$SRC")"
OUT_W="$(winpath "$OUT")"

echo "run.sh: javac -encoding UTF-8（编译期锁定编码，运行期字符集不影响判定）"
javac -encoding UTF-8 -d "$OUT_W" "$SRC_W" || die "编译失败"

# ---------- 运行 ----------
TS="$(date +%Y%m%d-%H%M%S)"
LOG="$LOG_DIR/a3-run-$TS.log"
DRV_W="$(winpath "$DRIVER")"

# 在仓库根下运行：运行器的 reportDir 默认是相对路径 scripts/concurrency/reports，
# 不 cd 的话从别处调用会把报告写到调用者目录里。
cd "$REPO" || die "无法进入 $REPO"

echo "run.sh: java -cp <classes>$SEP<driver> ConcurrencyRunner $*"
# --log-file 让报告能指回本次运行的完整日志。经 winpath 转成原生形态：Git Bash 自己
# 也会替原生程序转，但那是环境行为（MSYS_NO_PATHCONV / MSYS2_ARG_CONV_EXCL 一设就没了），
# 报告里的路径不该依赖调用者的环境变量。放在 "$@" 之后：调用方若显式传了 --log-file，
# 后出现的那个生效（解析是后者覆盖前者），调用方的显式意图优先。
java -cp "$OUT_W$SEP$DRV_W" ConcurrencyRunner "$@" --log-file "$(winpath "$LOG")" 2>&1 | tee "$LOG"
rc="${PIPESTATUS[0]}"

# 报告路径不在这里再打印一次：run.sh 无从知道 runId，只能挑「最新的 .json」，
# 那可能是上一次运行留下的 —— 报一个陈旧的路径比不报更坏。运行器自己的
# `report : <path>` 行才是权威（未落盘时它会打印 <<未落盘>>）。

echo ""
echo "run.sh: 日志 $LOG"
echo "run.sh: 驱动 $DRIVER（$DRIVER_FROM）"
echo "run.sh: 退出码 $rc"
exit "$rc"
