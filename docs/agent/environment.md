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

## ⚠️ 安全提醒：含明文密码的文件清单

**两个文件含明文密码，且都已进入 git 历史：**

| 文件 | 内容 | 引入 commit |
|---|---|---|
| `docs/agent/environment.md`（本文件） | SSH `ubuntu/123456`、MySQL `test/test123` | `5da4127` 起 |
| `Mall-management-server/src/main/resources/application-a3.properties` | 同一套 MySQL 口令作默认值（`${A3_DB_PASSWORD:test123}`），**且随 jar 一起分发** | `c37cb8e` |

仓库转公开前，必须二选一（**两个文件都适用**）：

1. **重写 git 历史**移除这两处（`git filter-repo` 或 BFG）。注意二者处置方式不同：`environment.md` 是记录性文档，重写后可整文件删除；`application-a3.properties` 是**源码文件**，重写后必须**继续留在仓库里**（保留 `${A3_DB_PASSWORD:...}` 占位形态、去掉真实默认值），否则 a3 profile 直接失效；
2. 保留但先确保这些密码已失效（如删除虚拟机重建）。

单纯改密码或加 `.gitignore` **不能**清除历史中的旧密码 —— 泄露面是历史里的 blob，不是工作区的当前值。
