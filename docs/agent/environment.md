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

## 用 `a3` profile 让后端连这台实例

后端默认连**本机开发库**（`application.properties`）。要连 VM 上的 A3 实例，起服务时挂 `a3` profile：

```bash
SPRING_PROFILES_ACTIVE=a3 ./mvnw spring-boot:run    # IDE 里则设同名环境变量
```

- profile 文件：`Mall-management-server/src/main/resources/application-a3.properties`（**含明文默认口令**，见文末安全提醒）
- 改动该文件前先读它：里面写明了「为什么必须独立实例 + 同名库」与**三条禁止项**（不许 `ddl-auto=create`、不许调 Hikari 池大小、不许把默认 URL 指向开发机）
- 库名与开发库**同名**（TD-13：39 处 schema 前缀硬编码在 SQL 里，切库名切不走）。故日志里看到 `mall_management_sql` **不能**说明连的是哪台 —— 要么看 `jdbcUrl` 的主机串，要么 `SELECT VERSION()`（开发库 8.0.32 / VM 8.4.11）
- 驱动：`pom.xml` 声明 8.0.33，实测直连 8.4.11 成功，**无需改 `pom.xml`**
- 上传目录已外置到 `${java.io.tmpdir}/a3-uploads/`，A3 期间不写仓库目录（TD-08）

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

- curl 提交中文一律用 `--data-urlencode`（curl 负责 URL 编码，不经 shell 的字节直传），或显式 `Content-Type: application/x-www-form-urlencoded; charset=UTF-8` + 已 UTF-8 编码的 body
- **更稳的做法**：手测走浏览器 / Postman，或写成 `.jsh`（`jshell`）脚本走 JDBC —— 本项目排查库内容时就是这么干的（临时脚本用完即弃，不进仓库）
- 判据若依赖「查重拦住了」这类**阴性结果**，先怀疑编码，再怀疑逻辑

> 归档说明：这条**不属于** `cache-layer.md §11` 的范畴（那里是缓存机制的坑），是**开发环境/工具链**的坑，故落在本文件。

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
