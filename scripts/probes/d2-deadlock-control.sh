#!/bin/bash
# 【仪器对照 · 不是业务实验】证明「死锁确实被 MySQL 检测到、且能被我们读到」。
#
# 为什么必须做：本实验的主结论可能是「0 次死锁」。若检测链本身是坏的（计数器不动、
#   INNODB STATUS 读不到），那个 0 与「真的没有」不可区分 —— 本仓的老毛病
#   （校验器报 OK ≠ 没问题）。故先用两个会话**主动**造一次死锁，证明判据是活的。
#
# ⚠️ 用的是 `goodstype` 的**只读锁 + ROLLBACK**：不写任何一列、不回滚任何数据。
#   两个会话反序 `SELECT ... FOR UPDATE` 同一对行 ⇒ 天然构成环。
#
# 用法: bash scripts/probes/d2-deadlock-control.sh
set -u
ROOT="C:/Users/86159/Desktop/软件工程/期末项目"
P="$ROOT/Mall-management-server/src/main/resources/application-dev.properties"
unph() { sed 's/^\${[^:]*://; s/}$//'; }
DB_URL=$(grep -m1 '^spring.datasource.url=' "$P" | cut -d= -f2- | unph)
DB_USER=$(grep -m1 '^spring.datasource.username=' "$P" | cut -d= -f2- | unph)
DB_PASS=$(grep -m1 '^spring.datasource.password=' "$P" | cut -d= -f2- | unph)
REST=${DB_URL#jdbc:mysql://}; HOSTPORT=${REST%%/*}; DBNAME=${REST#*/}; DBNAME=${DBNAME%%\?*}
HOST=${HOSTPORT%%:*}; PORT=${HOSTPORT##*:}
export MYSQL_PWD="$DB_PASS"
MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"

# 两个会话：各自抓一把行、睡 2 秒、再去抓对方手里那把 ⇒ 环。
A="SET SESSION innodb_lock_wait_timeout=5;
   START TRANSACTION;
   SELECT Id FROM goodstype WHERE Id=1 FOR UPDATE;
   SELECT SLEEP(2);
   SELECT Id FROM goodstype WHERE Id=2 FOR UPDATE;
   ROLLBACK;"
B="SET SESSION innodb_lock_wait_timeout=5;
   START TRANSACTION;
   SELECT Id FROM goodstype WHERE Id=2 FOR UPDATE;
   SELECT SLEEP(2);
   SELECT Id FROM goodstype WHERE Id=1 FOR UPDATE;
   ROLLBACK;"

echo "===== 会话 A（1→2）启动 ====="
"$MYSQL" -h "$HOST" -P "$PORT" -u "$DB_USER" -D "$DBNAME" --default-character-set=utf8mb4 -e "$A" > "$ROOT/_work/d2/ctl-a.txt" 2>&1 &
PA=$!
sleep 0.3
echo "===== 会话 B（2→1）启动 ====="
"$MYSQL" -h "$HOST" -P "$PORT" -u "$DB_USER" -D "$DBNAME" --default-character-set=utf8mb4 -e "$B" > "$ROOT/_work/d2/ctl-b.txt" 2>&1 &
PB=$!
wait $PA; wait $PB

echo "----- A 输出 -----"; cat "$ROOT/_work/d2/ctl-a.txt"
echo "----- B 输出 -----"; cat "$ROOT/_work/d2/ctl-b.txt"
echo
echo "===== 计数器（对照前应为 0，对照后 ER_LOCK_DEADLOCK 应 ≥ 1）====="
"$MYSQL" -h "$HOST" -P "$PORT" -u "$DB_USER" -D "$DBNAME" --table \
  -e "SELECT ERROR_NUMBER, ERROR_NAME, SUM_ERROR_RAISED FROM performance_schema.events_errors_summary_global_by_error WHERE ERROR_NUMBER IN (1213,1205);"
echo
echo "===== INNODB STATUS 是否出现 LATEST DETECTED DEADLOCK ====="
"$MYSQL" -h "$HOST" -P "$PORT" -u "$DB_USER" -D "$DBNAME" -e "SHOW ENGINE INNODB STATUS\G" \
  | grep -c "LATEST DETECTED DEADLOCK" | sed 's/^/命中行数: /'
