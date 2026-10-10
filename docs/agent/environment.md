# 开发环境信息

## A3 虚拟机 MySQL 环境（VMware）

| 项 | 值 |
|---|---|
| 虚拟机 IP | 192.168.229.129 |
| 虚拟机文件 | `D:\VMware\VMs\Ubuntu-MySQL-A3.vmx` |
| SSH 用户 | ubuntu |
| SSH 密码 | 123456 |
| MySQL 端口 | 3306 |
| MySQL 数据库 | mall_management_sql |
| MySQL 用户名 | test |
| MySQL 密码 | test123 |
| 服务端版本 | 8.4.11-0ubuntu0.26.04.1（`SELECT VERSION()` 实测） |
| 表数量 | 11（已导入 mall_management_sql.sql） |

## A3 虚拟机 Redis 环境（VMware）

同一台 VM（192.168.229.129）上另装了 Redis。

| 项 | 值 |
|---|---|
| 版本 | redis-server 8.0.5（`Redis server v=8.0.5 ... bits=64`），redis-cli 8.0.5 |
| 端口 | 6379 |
| 监听 | `bind 0.0.0.0 -::1`（IPv4 全接口 + IPv6 回环） |
| 密码 | 有，`123456`（`requirepass`） |
| 配置文件 | `/etc/redis/redis.conf` |
| 原始配置备份 | `/etc/redis/redis.conf.a3bak`（**改动前的原版**：`bind 127.0.0.1 -::1` + `protected-mode yes` + 无密码） |
| systemd 服务 | `redis-server`（另有软链别名 `redis.service`），已 `enabled` 开机自启 |
| 防火墙 | `ufw` 为 `inactive`，无需放行 |
| 包来源 | `universe`，包名 `redis-server`（`5:8.0.5-1`） |

**不是 valkey。** Ubuntu 26.04 源里 `valkey-server` 是**独立包**（9.0.4，main），装 `redis-server` 不会牵连它 —— `apt-cache show redis-server` 显示依赖只有 `redis-tools`，实际只拉 `liblzf1 / redis-tools / redis-server` 三个；`which valkey-server` 为空。

### 相对原版做的三处改动

| 行 | 原值 | 现值 | 原因 |
|---|---|---|---|
| 88 | `bind 127.0.0.1 -::1` | `bind 0.0.0.0 -::1` | 让主机能连 |
| 112 | `protected-mode yes` | `protected-mode no` | 同上，配合 bind 放开 |
| 文末 | （无） | `requirepass 123456` | 加口令，抵消前两项放开的暴露面 |

- **`-::1` 不能省**：Java `InetAddress.getByName("localhost")` 可能优先解析 `::1`，只写 `0.0.0.0` 会让 Spring 配 `localhost:6379` 连不上。实测 `redis-cli -h 127.0.0.1` / `-h ::1` / `-h localhost` 三条路径均 `PONG`。
- `requirepass` 追加在**文件末尾**（原版第 1068 行的 `# requirepass foobared` 是注释，未动）。回滚删掉文末那段 `===== A3 项目新增 =====` 注释块即可。

### 连接与验证

```bash
redis-cli -h 192.168.229.129 -a 123456 --no-auth-warning ping   # → PONG
redis-cli -h 192.168.229.129 ping                               # → NOAUTH Authentication required.
```

| 检查 | 结果 |
|---|---|
| VM 内 `redis-cli -a 123456 ping` | `PONG` |
| VM 内 `redis-cli ping`（无密码） | `NOAUTH Authentication required.` |
| VM 内 `redis-cli -a wrongpass ping` | `WRONGPASS invalid username-password pair or user is disabled.` |
| 主机侧 TCP + RESP | 通，`AUTH` → `+OK`，`PING` → `+PONG` |

⚠️ **主机（Git Bash）上没有 `nc`。** 凡写 `nc -zv` 处都要换等价手段：

```bash
# 1) bash /dev/tcp 发真实 RESP（推荐：验的是协议，不只是端口）
timeout 6 bash -c 'exec 3<>/dev/tcp/192.168.229.129/6379
printf "AUTH 123456\r\n" >&3; IFS= read -r -t 3 l <&3; echo "$l"'   # → +OK

# 2) PowerShell 只验端口
powershell.exe -NoProfile -Command "(Test-NetConnection -ComputerName 192.168.229.129 -Port 6379).TcpTestSucceeded"
```

两个坑（都踩过）：

1. **别用 `head -c N` 读 RESP 回复**：非 tty 下 stdout 块缓冲，字节数凑不够就被 `timeout` 杀掉、缓冲区整个丢掉 —— 现象是「连上了却什么都没读到」。用 `IFS= read -r -t 3` 逐行读。
2. `/dev/tcp` 只能证 TCP 可达；**加密码后**「端口通」≠「能用」，认证是否生效要靠方式 1 实测出 `+OK` / `-NOAUTH`。

**主机的完整探针手段（2026-09-28 B1 验证用，方法留存）**：主机上**没有 redis-cli**，但用 **Python 标准库裸 RESP**（`socket` 手写 `AUTH` / `GET` / `SET` / `TTL` / `EXISTS` / `INFO commandstats`）就能完整探针 —— B1 验证「L2 键是否存在、TTL 剩多少、本轮实际发了几条命令」全靠它（**`commandstats` 的增量是「L1 真的挡住了网络」的判据**）。验证「L2 恢复」分支同理：**主机侧起一个 TCP 转发**指向 VM 的 `6379`，应用指向转发器，转发器的停/起就造出「端点不可达 → 可达」的翻转 —— 不必重启应用，也不必碰 VM 上的 Redis 进程。**方法记在本文件，脚本用完即删、不进仓库**（同下文字符集坑末尾的约定）。

## 用 `dev` / `a3` profile 让后端连这台实例

后端默认连**本机开发库**（`application.properties`）。要连 VM 上的实例，起服务时挂 profile ——
**日常用 `dev`，CI 用 `a3`**（2026-10-11 职责分离，见 `tech-debt.md` TD-35）：

```bash
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run    # 日常开发 / 手验 / 攻击模拟器（job 全开）
SPRING_PROFILES_ACTIVE=a3  ./mvnw spring-boot:run    # 仅 CI 的 A3 并发门禁（job 全关）
```

- profile 文件：`Mall-management-server/src/main/resources/application-dev.properties` 与 `application-a3.properties`（**含明文默认口令**，见文末安全提醒）
- ⚠️ **`a3` 是 CI 专用，别拿它做日常 / 手验** —— 它的三个「会自己醒来的东西」开关全关（压测确定性），日常跑它会**看不到**物流轨迹 / 超时取消等功能。`dev` 是它的孪生，只把这三个开关打开。
- 改动 `application-a3.properties` 前先读它：里面写明了「为什么必须独立实例 + 同名库」与**三条禁止项**（不许 `ddl-auto=create`、不许调 Hikari 池大小、不许把默认 URL 指向开发机）；`application-dev.properties` 是它的复制，**改 a3 的「连库 / Redis / TTL」要同步改 dev**。
- 库名与开发库**同名**（TD-13：39 处 schema 前缀硬编码在 SQL 里，切库名切不走）。故日志里看到 `mall_management_sql` **不能**说明连的是哪台 —— 要么看 `jdbcUrl` 的主机串，要么 `SELECT VERSION()`（开发库 8.0.32 / VM 8.4.11）
- 驱动：`pom.xml` 声明 8.0.33，实测直连 8.4.11 成功，**无需改 `pom.xml`**
- 上传目录已外置到 `${java.io.tmpdir}/a3-uploads/`（dev 为 `dev-uploads/`），不写仓库目录（TD-08）
- **两级缓存参数在 `application.properties`（不在 a3 profile）**：`app.cache.caffeine.time-to-live=60s` / `app.cache.caffeine.max-size=1000`，装配见 `CacheConfig`（`@Primary CacheManager` = Caffeine L1 + Redis L2，设计见 `cache-layer.md §8`）。**不变量：L1 TTL ≤ `spring.cache.redis.time-to-live`**（dev 与 a3 两个 profile 均为 `5m`）—— L1 没有跨节点失效通道，违反时陈旧窗口被放大到 L1 TTL，故启动期 WARN（`CacheConfig.warnIfL1OutlivesL2`）。自检：启动日志有一行 `两级缓存已装配：L1 Caffeine(maxSize=1000, ttl=PT1M) -> L2 Redis(ttl=PT5M)`，**用 ASCII 片段 `Caffeine(maxSize=` 检索**（日志为 GBK 字节，中文 pattern 匹配不到）—— 属性有没有被真正读到，看这一行，不靠推断

### 导入数据（在 guest 上执行）

```bash
echo 123456 | sudo -S -p '' mysql < /tmp/mall_management_sql.sql
```

- **必须 `sudo`**：guest 上 `root@localhost` 是 `auth_socket`，root 无法用口令走 TCP 登录
- 无需 `sed` 过滤：文件自带 `CREATE DATABASE mall_management_sql` + `USE`，与目标库名一致（TD-12）
- ⚠️ 该文件含 **11 条 `DROP TABLE IF EXISTS`** → 只可用于 VM / CI 的专用实例，**绝不可指向有真实数据的库**。清库至今只有这一条路：脚本的 `--reset-db` 是死参数（TD-16）

## vmrun 通道配方（重做 VM 操作时复用）

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
vmrun -T ws -gu ubuntu -gp 123456 runProgramInGuest "D:\VMware\VMs\Ubuntu-MySQL-A3.vmx" /bin/bash -c "..."
```

1. `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` —— 否则 Git Bash 会把 `/bin/sh`、`/bin/bash` 改写成 Windows 路径，`runProgramInGuest` 一律报「未找到文件」
2. **vmrun 不回传 guest 的 stdout** → 把输出重定向到 guest 上的文件，再用 `CopyFileFromGuestToHost` 取回
3. `getGuestIPAddress` 的输出**含 CRLF**，不要用 `read -r` 捕获（`\r` 会被并进参数）
4. `-T ws` = VMware Workstation；`vmrun -T ws list` 可查当前 VM 路径

> 文件搬运用 `CopyFileFromHostToGuest` / `CopyFileFromGuestToHost`；要改 guest 上的文件内容，用 `runProgramInGuest` + guest 侧 `sh -c`。

## 主机侧手测的字符集坑（2026-09-27 实测踩中）

**症状**：在 Git Bash（本机代码页为 **GBK**）里手敲 `curl -X POST -d "GoodType=电子用品&..."` 打中文接口，**看起来成功、HTTP 200、`success:true`**，但库里多了一行乱码，而且**去重逻辑完全没拦住**。

**成因**：curl 把 shell 里的中文字符按 **GBK** 编码成字节发出去，服务端按 **UTF-8** 解码 → 得到一串替换字符 → 业务侧的 `SELECT COUNT(*) WHERE GoodType = ?` 用**正确的 UTF-8 中文**去查 → 匹配不上（查到的是乱码行）→ 判定「类型不存在」→ 静默 INSERT。

**实测证据**（B1 缓存验证时踩中）：

```
Id=598  GoodType HEX = EFBFBD EFBFBD EFBFBD EFBFBD EFBFBD EFBFBD C6B7
        └─ 6 个 U+FFFD（UTF-8 替换字符）        └─ GBK 的「品」残留
```

`EFBFBD` 是 U+FFFD 的 UTF-8 编码 —— 出现它就意味着**某一环拿错误编码解过字节**，是编码不匹配的指纹。

**与 A3 的字符集缺陷同源**：脚本侧 `run.sh` 要用 `javac -encoding UTF-8` 两步编译，本质是同一个问题的另一面 —— Windows 默认编码不是 UTF-8，凡「中文必经之路」都要显式指定编码。**A3 脚本不受影响**（它走 JDBC，连接参数 `characterEncoding` 已固定），**只有手测会中招**。

**处置**：

- ⚠️ **`--data-urlencode` 救不了这一条 —— 2026-10-11 实测**（本条推翻了此前写在这里的旧建议；触发事例见下）。本机 `curl` 是 `/mingw64/bin/curl`（`x86_64-w64-mingw32`，**原生 Windows 程序**，非 msys-linked）⇒ Git Bash spawn 它时，**中文 argv 被折成 ANSI 代码页（本机 CP936/GBK）**，**与脚本文件自身的编码无关**（实测：脚本是 UTF-8、`printf` 吐出来也是 UTF-8，发出去的却是 GBK）。三种写法打本地 echo 服务的**服务端实收字节**：
  - 字面 `-d 'ProductInformation=取消核验:1'` → `…3D C8A1CFFBBACBD1E9 3A31` = **GBK**（乱码根因）
  - `--data-urlencode` 同串 → `…3D %C8%A1%CF%FB%BA%CB%D1%E9 3A31` = **仍是 GBK**，只是百分号转义；服务端 URL 解码后**与上一条逐字节相同** ⇒ **旧建议无效**
  - `--data-binary @utf8文件` → `…3D E58F96E6B688E6A0B8E9AA8C 3A31` = **UTF-8**，正确
  - 探针：`scripts/probes/c3b4p-curl-encoding.sh`；原始产物：`docs/agent/evidence/c3-block4-patch/curl-encoding-capture.txt`
- **处置（按可靠性排序）**：① **探针 / 脚本里的造数一律用纯 ASCII** —— 探针验的是状态位 / CAS / 时序，**不需要中文**；② 非要中文则 `--data-binary @utf8文件`，或显式 `Content-Type: application/x-www-form-urlencoded; charset=UTF-8` + 已是 UTF-8 的 body；③ **绝不**在 Git Bash 里用字面中文 `-d`
- **真踩了也别慌：乱码是确定性的、可原位修**。库里那两行（`order.ProductInformation`，2026-10-10 探针所造）HEX = `C8A1 EFBFBD×6 3A31` —— 正是「GBK 字节被按 UTF-8 解码」（`C8A1`→U+0221，`CF FB`/`BA DB`/`D1 E9` 各→ 2 个 U+FFFD）再编码的结果。修法用**纯 ASCII** 的 `UPDATE … SET c = CONVERT(UNHEX('<目标 UTF-8 的 HEX>') USING utf8mb4) WHERE …`（**别**在 SQL 里写中文字面量，同一个坑）；2026-10-11 已如此修，改后 `CHAR_LENGTH` = 6 与预期相符、全表残留 U+FFFD 归零
- **更稳的做法**：手测走浏览器 / Postman，或写成 `.jsh`（`jshell`）脚本走 JDBC —— 本项目排查库内容时就是这么干的（临时脚本用完即弃，不进仓库）
- 判据若依赖「查重拦住了」这类**阴性结果**，先怀疑编码，再怀疑逻辑

> 归档说明：这条**不属于** `cache-layer.md §11` 的范畴（那里是缓存机制的坑），是**开发环境/工具链**的坑，故落在本文件。

## ⚠️ 主机 headless 浏览器探针的坑：滚动条宽恒为 0（2026-10-07 实测，D 笔 commit 7c 踩中）

**本机 headless Edge 154 的竖向滚动条布局宽恒为 0。**

```
pageH = 2186（视口 900）⇒ innerWidth − document.documentElement.clientWidth = 0
```

**后果**：`100vw` 与 `clientWidth` 在探针里**恒等** ⇒ `.app-shell { width:100vw }` 这类
「vw 含滚动条宽」的横向溢出**永远量不到**，`overflowX = 0` 的断言**必然全绿**。
这正是「校验器报 OK ≠ 没问题」——未覆盖的失效模式会以 `OK` 报出来。

**三条强制手段全部无效（已实跑，别再试）**：

1. `--disable-features=OverlayScrollbar`
2. `--disable-features=OverlayScrollbars`
3. `--disable-features=OverlayScrollbar,FluentOverlayScrollbar`
4. 注入 `html::-webkit-scrollbar{width:15px}` + `html{overflow-y:scroll}`

**处置（写探针时必须照办）**：

- 报 `overflowX = 0` 时**必须同时说明它只证明了「内容没有横向溢出」**（这层是真信号：
  比较 `shellW` / `element.right` 与 `clientWidth`），**证不了**含滚动条的情形；
- 要真验 `100vw`，只能上**非 headless 的真浏览器**（人眼），或按规范直接判定并告知用户。

> 同款记录另见记忆 `headless-scrollbar-width-zero`。本条的适用范围**不限 D 笔** ——
> 凡在本机用 headless 跑「滚动条 / 视口宽」类断言的地方都受它影响。

## ⚠️ 安全提醒：含明文密码的文件清单

**两个文件含明文密码，且都已进入 git 历史：**

| 文件 | 内容 | 引入 commit |
|---|---|---|
| `docs/agent/environment.md`（本文件） | SSH `ubuntu/123456`、MySQL `test/test123`、**Redis `requirepass 123456`** | `5da4127` 起；Redis 口令为 2026-09-27 新增 |
| `Mall-management-server/src/main/resources/application-a3.properties` | 同一套 MySQL 口令作默认值（`${A3_DB_PASSWORD:test123}`），**且随 jar 一起分发** | `c37cb8e` |

仓库转公开前，必须二选一（**两个文件都适用**）：

1. **重写 git 历史**移除这两处（`git filter-repo` 或 BFG）。注意二者处置方式不同：`environment.md` 是记录性文档，重写后可整文件删除；`application-a3.properties` 是**源码文件**，重写后必须**继续留在仓库里**（保留 `${A3_DB_PASSWORD:...}` 占位形态、去掉真实默认值），否则 a3 profile 直接失效；
2. 保留但先确保这些密码已失效（如删除虚拟机重建）。

单纯改密码或加 `.gitignore` **不能**清除历史中的旧密码 —— 泄露面是历史里的 blob，不是工作区的当前值。
