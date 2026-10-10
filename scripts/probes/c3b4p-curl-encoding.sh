#!/usr/bin/env bash
# c3b4p-curl-encoding.sh —— 定位「探针造数据中文变乱码」的**真实机制**（2026-10-11）。
#
# 【为什么要这个探针】VM 库里留下两行乱码（CU320435A/C），HEX = C8A1 EFBFBD×6 3A31。
#   事前有两种候选解释，**必须实测而不是推断**：
#     ① shell 把中文按 GBK 编码交给 curl；② curl 收到的 argv 在 Windows 上经 ANSI 代码页转换。
#   既然是 argv 转换，那「用 --data-urlencode」这条既有建议**可能也救不了** —— 要验。
#
# 【机制】本机 curl 是 `/mingw64/bin/curl`（`x86_64-w64-mingw32`，Schannel）——
#   **原生 Windows 程序**。Git Bash 的 bash 是 msys-linked，spawn 原生子进程时把 argv
#   从 UTF-8 转成 UTF-16；而原生程序的 `main()`（非 wmain）由 CRT 再转成 **ANSI 代码页
#   （本机 = CP936/GBK）** ⇒ curl 拿到的是**已被折成 GBK 的字节**，与脚本文件本身的编码无关。
#
# 【判据】三种写法各打一次本地 echo 服务，比对**服务端收到的 body 字节**：
#   A 字面中文 -d          → 期望 GBK 字节（乱码根因）
#   B --data-urlencode     → 若仍 GBK ⇒ 既有建议**不足**
#   C --data-binary @文件   → 期望 UTF-8 字节（正确做法）
#
# 只打 127.0.0.1，不碰 VM / 不碰业务库。用完即弃。
set -u
ROOT="C:/Users/86159/Desktop/软件工程/期末项目"
WORK="$ROOT/_work"
PORT=18099
OUT="$WORK/curl-encoding-capture.txt"
BODY="$WORK/curl-encoding-body.txt"
: > "$OUT"
printf 'ProductInformation=%s' '取消核验:1' > "$BODY"

echo "== 0. 编码基线 =="
echo -n "  脚本里这 4 个字的字节 : "; printf '取消核验' | od -An -tx1 | tr -s ' '
echo    "  GBK 编码同 4 个字     :$(python -c "print(' '+' '.join('%02X'%b for b in '取消核验'.encode('gbk')))")"
echo    "  UTF-8 编码同 4 个字   :$(python -c "print(' '+' '.join('%02X'%b for b in '取消核验'.encode('utf-8')))")"

echo "== 1. 起本地 echo 服务（$PORT，只收字节）=="
python - "$PORT" "$OUT" <<'PYEOF' &
import http.server, sys, base64
port, out = int(sys.argv[1]), sys.argv[2]
N = [0]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n)
        N[0] += 1
        with open(out, 'a', encoding='utf-8') as f:
            f.write('%d\t%s\n' % (N[0], body.hex().upper()))
        self.send_response(200); self.send_header('Content-Length','2'); self.end_headers()
        self.wfile.write(b'ok')
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', port), H).serve_forever()
PYEOF
SRV=$!
up=0
for i in $(seq 1 20); do
  if curl -s -o /dev/null -m 2 -X POST "http://127.0.0.1:$PORT/" -d "probe=1"; then up=1; break; fi
  sleep 0.5
done
[ "$up" = 1 ] || { echo "  ❌ echo 服务没起来"; kill $SRV 2>/dev/null; exit 1; }
echo "  ✅ 就绪"
: > "$OUT"   # 丢掉探活那一发

echo "== 2. 三种写法各打一发（同一份中文）=="
curl -s -o /dev/null -X POST "http://127.0.0.1:$PORT/" -d 'ProductInformation=取消核验:1'
curl -s -o /dev/null -X POST "http://127.0.0.1:$PORT/" --data-urlencode 'ProductInformation=取消核验:1'
curl -s -o /dev/null -X POST "http://127.0.0.1:$PORT/" --data-binary "@$BODY"

kill $SRV 2>/dev/null; wait $SRV 2>/dev/null

echo "== 3. 服务端实收（HEX）+ 判定 =="
python - "$OUT" <<'PYEOF'
import sys
want_gbk   = 'ProductInformation=' .encode().hex().upper() + '取消核验:1'.encode('gbk').hex().upper()
want_utf8  = 'ProductInformation=' .encode().hex().upper() + '取消核验:1'.encode('utf-8').hex().upper()
names = ['A 字面 -d', 'B --data-urlencode', 'C --data-binary @file']
for i, line in enumerate(open(sys.argv[1], encoding='utf-8')):
    line = line.rstrip('\n')
    if not line: continue
    hex_ = line.split('\t', 1)[1]
    tag = '**GBK（乱码根因）**' if hex_ == want_gbk else ('UTF-8（正确）' if hex_ == want_utf8 else '其它')
    print('  %-22s %s  -> %s' % (names[i] if i < len(names) else '?', hex_, tag))
print()
print('  期望 GBK  :', want_gbk)
print('  期望 UTF-8:', want_utf8)
PYEOF
echo
echo "== 4. 结论 =="
echo "  · 与库里那两行（C8A1 EFBFBD×6 3A31）比对：该行 = 上面 A/B 的字节被服务端按 UTF-8 解码"
echo "    （C8A1 解成 U+0221、CF FB / BA DB / D1 E9 各解成 2 个 U+FFFD）后重编码的结果。"
echo "DONE"
