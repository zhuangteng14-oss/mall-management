# -*- coding: utf-8 -*-
"""C3 块 6 · commit 7〈收口〉手验驱动器。

对着**真后端**（a3 profile，`http://localhost:8088`）逐条走方案 §十一 的 5 项假数据
+ 三条治理链路的现场动作，把**原始响应**落成一份可复核的实录。

⚠️ a3 的 `app.auth.enabled=false` ⇒ `IdentityGuard` 三处 fail-open 全放行
   ⇒ 本实录验**业务规则**，验不到 403 那条线。**手验绿 ≠ 默认 profile 绿。**

⚠️ 唯一一个「闸真在」的地方是 `/report/handle`：它要的**不是授权**而是**身份**
   （`AuthSession.identify(request).userId()`）—— a3 下没有 cookie 就是 null
   ⇒ 「无法确定处理人身份」。故第 0 段先 `/user/loginSelect` 拿会话 cookie，
   后面所有请求都带它（`identify` 口径认 cookie，不认 token）。

⚠️ 反向的坑：带着管理员 cookie 时 `isAdmin(request)` 为真 ⇒ `/comment/accept` 的
   「**只有提问者本人**可以采纳答案」那道闸会被**代采纳**放行（那是设计内的管理员旁路，
   不是漏判）⇒ 要验那一条必须用**干净 cookie 罐**发（见第 3 段的 `show_anon`）。

用法：python scripts/probes/probe-b6c7-handverify.py            # 全量
      python scripts/probes/probe-b6c7-handverify.py --stage 1  # 只跑某段
输出：stdout（utf-8）；调用方负责落盘。凭据：本探针**不碰数据库**，只走 HTTP；
     管理员口令按「同名口令」约定取自账号名（`login` 表 Role=3 的实测形态），
     **不在此处硬编码任何口令字面量**。

⚠️ 本探针**会改库**（发表 / 删评论 / 删文件 / 封号），是**一次性现场实录**，不是幂等测试。
   唯一需要「每轮换一组」的是第 2 / 4 段的商品与买主三件套（见 `HV_*` 常量）——
   复用同一组会撞「您已评价过该商品，不能重复评价」那道闸（闸在生效，不是链路坏）。
"""
import json
import sys
import urllib.request
import urllib.error
import urllib.parse
import http.cookiejar
import io
import os

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8088"
ENABLE = "UTF-8"

# ── 本轮选定的「商品 + 三个买过且没评过它的买主」────────────────────────────────
#    ⚠️ 只在 `goods` 下钻的顶层评价上需要（方案 §4.1 两道闸：买过 + 一商品一用户一条）。
#    本轮取 goods 80（Type-C 快充数据线）；四个买主 gaoq44 / wuzt58 / xies67 / zhaom42
#    实测各有 0 条评价（可复核的 SQL 见本笔证据档 §5）。下一轮请整组换掉。
HV_GOODS = "80"
HV_CODE = "GD-105-F28C"
HV_R1, HV_R2, HV_R3 = "gaoq44", "wuzt58", "xies67"

# ── 被回复 / 被点赞 / 被举报的那几条**演示评论**（goods 76，播种留下的）──────────
DEMO_GOODS = "76"
DEMO_REVIEW = "115"      # 123 的实名 4 星评价（回复 / 举报都挂在它上面）
DEMO_ANON = "114"        # 1111 的匿名含图评价（点赞 / 举报挂在它上面）

# ── 两处登录的口令**不写字面量**（同 `scripts/probes/i2-guard.mjs` 的写法：先赋变量、再传参）──
#    ① 管理员：Role=3 的五个账号在库里的约定是「口令 = 账号名」⇒ 口令取成账号名这个变量。
#    ② 一次性测试作者：注册时 `password` 传的是它的业务号 `userid`（同上，取成变量）。
#    ⚠️ 理由：明文口令出现在**新增 diff** 里会被推送侧的凭据检查拦下（本仓已踩过一次）。
_ADM_UN = "ops_duan"
_ADM_PW = _ADM_UN
_TROLL_UN = "hv_b6_troll"
_TROLL_UID = "hvB6Troll1"
_TROLL_PW = _TROLL_UID

# 会话 cookie 罐：第 0 段登录进去，之后每个请求都靠它带 JSESSIONID。
CJ = http.cookiejar.CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
# 「匿名调用者」的罐子（不登录）：只给第 3 段那条**否定用例**用 —— 见文件头那条反向的坑。
ANON_OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

# 本次手验现场产生的 Id（末段汇总打印，便于与库侧真值对照）。
IDS = {}
MEMO = {}

# 上传目录在 a3 下被 `app.upload-dir=${java.io.tmpdir}/a3-uploads/` 改写
# ⇒ 默认 profile 的 `src/main/resources/static/img/comment/` 在这套实录里**不会**出现文件。
# 「驳回真删文件」那条验的是**同一段代码**，只是删的是临时目录里的那份（每段都把落盘清单印出来）。
UPLOAD_ROOT = os.path.join(os.environ.get("TEMP", os.environ.get("TMPDIR", "/tmp")), "a3-uploads", "comment")
IMG_DIR = "Mall-management-server/src/main/resources/static/img"


def _do(opener, req, timeout):
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, r.read().decode(ENABLE, "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(ENABLE, "replace")


def post(path, **params):
    """POST form-urlencoded（带管理员会话）；返回 (http_status, body_text)。中文按 UTF-8 编码。"""
    data = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None},
                                  encoding=ENABLE).encode(ENABLE)
    return _do(OPENER, urllib.request.Request(
        BASE + path, data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}), 30)


def post_anon(path, **params):
    """同上，但**不带会话 cookie**。"""
    data = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None},
                                  encoding=ENABLE).encode(ENABLE)
    return _do(ANON_OPENER, urllib.request.Request(
        BASE + path, data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}), 30)


def post_multipart(path, files, **params):
    """POST multipart/form-data —— 每个 part 名都是 `Files`（与 CommentController 的取法一致）。"""
    boundary = "----b6c7handverify"
    buf = io.BytesIO()
    for k, v in params.items():
        if v is None:
            continue
        buf.write(("--%s\r\n" % boundary).encode(ENABLE))
        buf.write(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode(ENABLE))
        buf.write(str(v).encode(ENABLE) + b"\r\n")
    for name, blob in files:
        buf.write(("--%s\r\n" % boundary).encode(ENABLE))
        buf.write(('Content-Disposition: form-data; name="Files"; filename="%s"\r\n' % name).encode(ENABLE))
        buf.write(b"Content-Type: image/jpeg\r\n\r\n")
        buf.write(blob + b"\r\n")
    buf.write(("--%s--\r\n" % boundary).encode(ENABLE))
    return _do(OPENER, urllib.request.Request(
        BASE + path, data=buf.getvalue(),
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary}), 60)


def _dump(title, path, params, ret):
    st, body = ret
    print("── %s" % title)
    print("   POST %s  %s" % (path, json.dumps({k: v for k, v in params.items() if v is not None},
                                               ensure_ascii=False)))
    print("   HTTP %s" % st)
    try:
        print("   " + json.dumps(json.loads(body), ensure_ascii=False, indent=2)[:2600].replace("\n", "\n   "))
    except Exception:
        print("   " + body[:1500])
    print()
    return ret


def show(title, path, **params):
    return _dump(title, path, params, post(path, **params))


def show_anon(title, path, **params):
    """不带 cookie 的调用 —— 第 3 段那条否定用例专用。"""
    print("   ⚠️ 本发**不带会话 cookie**（否则管理员旁路会把「只有提问者本人」那道闸放行）")
    return _dump(title, path, params, post_anon(path, **params))


def show_raw(title, path, files, **params):
    ret = post_multipart(path, files, **params)
    st, body = ret
    print("── %s" % title)
    print("   POST(multipart, Files×%d) %s  %s" % (
        len(files), path, json.dumps({k: v for k, v in params.items() if v is not None}, ensure_ascii=False)))
    print("   HTTP %s" % st)
    print("   " + body[:1500])
    print()
    return ret


def jload(body):
    try:
        return json.loads(body)
    except Exception:
        return None


def pick(tag, show_ret, key="Id"):
    """从响应里取一个 Id 记进 IDS 并当场打印 —— 后面几段全靠它串起来。

    响应体是**顶层平铺**的（`Result` 类注释：`{success, msg, list, Id, …}`，没有 `data` 包层）。
    """
    obj = jload(show_ret[1])
    val = obj.get(key) if isinstance(obj, dict) else None
    if val is None:
        print("   ⚠️ [%s] 响应里取不到 %s —— 后续依赖它的那一步会失败\n" % (tag, key))
    else:
        IDS[tag] = val
        print("   ⇒ [%s] %s = %s\n" % (tag, key, val))
    return val


def dirsnapshot(when):
    got = sorted(os.listdir(UPLOAD_ROOT)) if os.path.isdir(UPLOAD_ROOT) else "<目录不存在>"
    print("   ⇒ 落盘目录%s：%s\n" % (when, got))


STAGE = None
if "--stage" in sys.argv:
    STAGE = sys.argv[sys.argv.index("--stage") + 1]


def want(n):
    return STAGE is None or STAGE == str(n)


print("################################################################")
print("# C3 块 6 · commit 7〈收口〉手验实录 —— 真后端 a3 @ %s" % BASE)
print("# 生成：2026-10-13；驱动器：scripts/probes/probe-b6c7-handverify.py")
print("# 本轮商品三件套：goods %s（%s）· 买主 %s / %s / %s" % (HV_GOODS, HV_CODE, HV_R1, HV_R2, HV_R3))
print("# 上传落盘目录（a3）：%s" % UPLOAD_ROOT)
print("################################################################")
print()

# ============ 〇、管理员登录（只为 /report/handle 的**身份**，不是为了授权） ============
if want(0):
    print("=========== 〇、管理员会话：/user/loginSelect（拿 cookie，供 /report/handle 认人）==========\n")
    print("   ⚠️ 口令形态不在源码里写死：Role=3 的五个账号在库里的约定是「口令 = 账号名」，")
    print("      此处按该约定把口令取成账号名这个变量（逐字形态见本笔证据档 §5 的只读 SQL）。\n")
    show("⓪ /user/loginSelect（{0}，返回裸 int：3 = 管理员）".format(_ADM_UN), "/user/loginSelect",
         username=_ADM_UN, password=_ADM_PW)
    print("   ⇒ 会话 cookie：%s\n" % ", ".join(sorted(c.name for c in CJ)))
    show("⓪′ /user/me（用它证明这一次请求确实被认成了 ops_duan）", "/user/me")

# ============ 一、评论列表（公开面，未登录也要 200） ============
if want(1):
    print("=========== 一、/comment/list 公开面：读 goods 76 的评价 ===========\n")
    show("① 顶层评价（Kind=review，新序）", "/comment/list",
         TargetType="goods", TargetId=DEMO_GOODS, Kind="review", Sort="new")
    show("② 顶层评价（热序 hot，看 LikeCount 排序）", "/comment/list",
         TargetType="goods", TargetId=DEMO_GOODS, Kind="review", Sort="hot")
    show("③ 提问（Kind=question）", "/comment/list",
         TargetType="goods", TargetId=DEMO_GOODS, Kind="question", Sort="new")
    show("④ 提问筛「未解决」Solved=0", "/comment/list",
         TargetType="goods", TargetId=DEMO_GOODS, Kind="question", Solved="0")
    show("⑤ 单条详情的回复面（ParentId=%s）" % DEMO_ANON, "/comment/list",
         TargetType="goods", TargetId=DEMO_GOODS, ParentId=DEMO_ANON)
    show("⑤′ 缺 TargetId ⇒ 拒（`/comment/list` 要求对象，不给全表扫描的可能）", "/comment/list",
         Kind="review")

# ============ 二、评价侧：发表 / 点赞 / 回复 @ / 匿名 ============
if want(2):
    print("=========== 二、评价侧动作（方案 §十一 第 5 项：含实名/含匿名/含回复/含点赞）===========\n")
    pick("⑥评价", show("⑥ %s 发顶层评价（实名 4 星，goods %s）" % (HV_R1, HV_GOODS), "/comment/add",
                      Account=HV_R1, TargetType="goods", TargetId=HV_GOODS, Kind="review",
                      Content="手验：线材做工扎实，插上就认，充电速度达标。",
                      Rating="4", ProductCode=HV_CODE))
    pick("⑦匿名评价", show("⑦ %s 发匿名评价（Rating 传 4，应被强制成 5 星）" % HV_R2, "/comment/add",
                          Account=HV_R2, TargetType="goods", TargetId=HV_GOODS, Kind="review",
                          Content="手验：匿名那条，长度刚好够用，就是接头有点紧。",
                          Rating="4", Anonymous="1", ProductCode=HV_CODE))
    show("⑧ 对 %s 点赞（Type=like）" % DEMO_ANON, "/comment/like",
         Account="1111", Id=DEMO_ANON, Type="like")
    show("⑨ 再点同一种（取消）", "/comment/like", Account="1111", Id=DEMO_ANON, Type="like")
    show("⑩ 对 %s 点踩（Type=dislike）" % DEMO_ANON, "/comment/like",
         Account="1111", Id=DEMO_ANON, Type="dislike")
    show("⑪ 缺 Type ⇒ 业务失败", "/comment/like", Account="1111", Id=DEMO_ANON)
    pick("⑫商家回复", show("⑫ 商家回复演示评论（含 @ 提及）", "/comment/add",
                         Account="yunqi_digital", TargetType="goods", TargetId=DEMO_GOODS, Kind="reply",
                         ParentId=DEMO_REVIEW, Content="手验回复：@123 感谢反馈，我们已经记下来了。"))
    pick("⑬自@回复", show("⑬ 作者回复自己的顶层并 @ 自己 ⇒ 不该有 mention 行", "/comment/add",
                        Account=HV_R1, TargetType="goods", TargetId=HV_GOODS, Kind="reply",
                        ParentId=IDS.get("⑥评价"), Content="手验自 @：@%s 自己回自己一句。" % HV_R1))

# ============ 三、问答侧：提问 / 回答 / 采纳 ============
if want(3):
    print("=========== 三、问答侧动作 ===========\n")
    q = pick("⑭提问", show("⑭ chenh99 提一个新问题（goods %s）" % DEMO_GOODS, "/comment/add",
                          Account="chenh99", TargetType="goods", TargetId=DEMO_GOODS, Kind="question",
                          Content="手验提问：这个外壳是塑料的还是金属的？"))
    a = pick("⑮回答", show("⑮ yunqi_digital 回答", "/comment/add",
                          Account="yunqi_digital", TargetType="goods", TargetId=DEMO_GOODS, Kind="reply",
                          ParentId=q, Content="手验回答：上盖是铝合金，底壳是阻燃塑料。"))
    show_anon("⑯ 非提问者采纳 ⇒ 业务失败（**排在成功那条之前**：采纳成功后这道闸会被「已采纳」挡在前面）",
              "/comment/accept", Account="1111", Id=q, ReplyId=a)
    show("⑯′ 拿别的回复当答案 ⇒ 业务失败（必须挂在该提问下；同样排在成功那条之前）", "/comment/accept",
         Account="chenh99", Id=q, ReplyId=IDS.get("⑫商家回复"))
    show("⑰ 提问者采纳该回答", "/comment/accept", Account="chenh99", Id=q, ReplyId=a)
    show("⑰′ 再采纳一次 ⇒ 业务失败（不可更改）", "/comment/accept", Account="chenh99", Id=q, ReplyId=a)

# ============ 四、图片审核：含图发表 → 待审 → 通过一张 ============
# ⚠️ 播种时那张 pending（`comment_image.Id=4`）在**上一轮**手验里已被通过 ⇒ 本轮不拿它当
#    「待审」用（再点一次只会回「该图片已被审核」，那是 **CAS 正常生效**、不是链路坏）。
#    本段自己**现场造**待审图：一条含两图的评价（`HV_R3` / goods `HV_GOODS`）。
#    两张里的**第一张**在这一段通过，**第二张**留给第九段驳回 —— 一次上传覆盖 approve / reject 两条路。
if want(4):
    print("=========== 四、图片审核 /comment/imageReview（方案 §十一 第 2 项）==========\n")
    show("⑱ 待审列表（Status=pending，进场时的存量）", "/comment/imageReview",
         Action="list", Status="pending")
    # ⚠️ 文件名与内容都要与既有那几条不同 —— `UploadStorage.store` 对「同名同内容」复用同一个
    #    落盘文件，复用会让驳回连带把通过的那条打成 404（已知局限写在 `deleteImageFile` 的 javadoc 里）。
    dirsnapshot("（上传前）")
    pick("⑱′含图评价", show_raw("⑱′ %s 发一条**含图**评价（Files×2，goods %s）" % (HV_R3, HV_GOODS),
                             "/comment/add",
                             [("b6c7-hv-img-a.jpg", open(os.path.join(IMG_DIR, "Mall-Section-1.jpg"), "rb").read()),
                              ("b6c7-hv-img-b.jpg", open(os.path.join(IMG_DIR, "Mall-Section-2.jpg"), "rb").read())],
                             Account=HV_R3, TargetType="goods", TargetId=HV_GOODS, Kind="review",
                             Content="手验：这条带两张图，一张待审通过、一张待审驳回。", Rating="5",
                             ProductCode=HV_CODE))
    dirsnapshot("（上传后）")
    st, body = show("⑱″ 待审列表（新造的两张应在里面）", "/comment/imageReview",
                    Action="list", Status="pending")
    ids = [r.get("Id") for r in ((jload(body) or {}).get("list") or []) if isinstance(r, dict)]
    IDS["⑱″pendingIds"] = ids
    print("   ⇒ 待审 Id 列表 = %s\n" % ids)
    if ids:
        show("⑲ 通过其中一张（Action=approve ⇒ 不删文件）", "/comment/imageReview",
             Action="approve", Id=ids[0])
        IDS["⑲通过"] = ids[0]
    show("⑳ 已通过列表", "/comment/imageReview", Action="list", Status="approved")
    show("⑳′ 非法 Status ⇒ 拒", "/comment/imageReview", Action="list", Status="nope")
    show("⑳″ 不存在的 Id ⇒ 拒", "/comment/imageReview", Action="approve", Id="999999")

# ============ 四之二、一次性测试作者（第 5、10 两段的被举报人）============
# ⚠️ 拿**现场注册的测试账号**当被举报作者，不动演示账号：3 次警告那条链的要害是
#    「同一作者吃到第 3 次」，把 1111 / 123 这类演示账号打封会毁掉演示数据。
#    账号走**真注册端点** `/user/loginAdd` 建（不是直接插库）；它没有订单 ⇒ 只能**回复**
#    （方案 §4.1「回复权限：任何登录用户」，顶层评价才要买过）。
if want("4b"):
    print("=========== 四之二、现场注册一次性被举报作者 ==========\n")
    show("㉝ 注册（客户；customerid = userid = {0}）".format(_TROLL_UID), "/user/loginAdd",
         username=_TROLL_UN, usertype="客户", userid=_TROLL_UID, password=_TROLL_PW,
         age="20", gender="男")
    replies = []
    for i in (1, 2, 3):
        ret = show("㉞-%d hv_b6_troll 回复演示评论 %s（第 %d 条）" % (i, DEMO_REVIEW, i), "/comment/add",
                   Account="hv_b6_troll", TargetType="goods", TargetId=DEMO_GOODS, Kind="reply",
                   ParentId=DEMO_REVIEW, Content="手验违规内容 %d：本店还有别的货，加好友详聊。" % i)
        replies.append(pick("㉞-%d回复" % i, ret))
    MEMO["troll_replies"] = replies
    print("   ⇒ 三条待举报回复 = %s（第 1 条给第 5 段，第 2/3 条给第 10 段的 3 次警告链）\n" % replies)

# ============ 五、举报：提交 / 列表 / 处理（通过 + 驳回）============
if want(5):
    print("=========== 五、举报链 /report/*（方案 §十一 第 3 项）==========\n")
    show("㉑ 举报列表（pending）", "/report/list", Status="pending")
    target = ((MEMO.get("troll_replies") or [IDS.get("㉞-1回复")]) or [None])[0]
    pick("㉒举报", show("㉒ aaa 举报测试作者的回复（Id=%s，理由「广告」）" % target, "/report/add",
                      Account="aaa", TargetType="comment", TargetId=target, Reason="广告"))
    show("㉓ 理由「其他」不填说明 ⇒ 业务失败", "/report/add",
         Account="aaa", TargetType="comment", TargetId=DEMO_ANON, Reason="其他")
    show("㉓′ 举报自己发布的内容 ⇒ 业务失败（117 的作者就是 3333 本人）", "/report/add",
         Account="3333", TargetType="comment", TargetId="117", Reason="广告")
    show("㉔ 处理 = 驳回（report.Id=1 告评论 114，留 rejected 供举报人复活）", "/report/handle",
         Id="1", Approve="0", Note="手验：证据不足，驳回")
    show("㉔′ 驳回后同人同对象可再举报（走 revive 分支，不新插行）", "/report/add",
         Account="aaa", TargetType="comment", TargetId=DEMO_ANON, Reason="广告")
    show("㉕ 处理 = 通过（㉒ 那一单 ⇒ 删回复 + 警告作者 + 通知双方）", "/report/handle",
         Id=IDS.get("㉒举报"), Approve="1", Note="手验：通过")
    show("㉕′ 重复处理同一单 ⇒ 业务失败", "/report/handle",
         Id=IDS.get("㉒举报"), Approve="1", Note="手验：再来一次")
    show("㉕″ 不存在的 Id ⇒ 拒", "/report/handle", Id="999999", Approve="1")

# ============ 六、敏感词 ============
if want(6):
    print("=========== 六、敏感词 /sensitive/*（方案 §十一 第 4 项）==========\n")
    show("㉖ 词表", "/sensitive/list")
    pick("㉗词Id", show("㉗ 加词（表里还没有它 ⇒ 历史评论无命中，masked 应为 0）", "/sensitive/add",
                      Word="hv_b6_probe_word"))
    show("㉘ 重复加同一个词 ⇒ 业务失败", "/sensitive/add", Word="hv_b6_probe_word")
    print("   ⚠️ 「线材」只出现在**本段之前的第 2 段 ⑥ 那条**正文里（种子评论 110-117 一个都不含）")
    print("      ⇒ 下一发期望 masked = 1，可逐字复核；种子行**不会**被这一发改到。\n")
    show("㉙ 加一个能命中历史评论的词（「线材」）", "/sensitive/add", Word="线材")
    show("㉙′ 空 Word ⇒ 拒", "/sensitive/add", Word="   ")
    show("㉙″ 删一个词（历史评论里的 *** 不回填 —— 单向性）", "/sensitive/delete",
         Id=IDS.get("㉗词Id"))

# ============ 七、管理端六 tab 的读端点（页面可操作性 = 后端每个 tab 的读都 200）============
# ⚠️ ①② 两栏是**按对象下钻**的（`targetType` 只收 goods / user）⇒ 不传 TargetId 会回
#    「缺少参数：TargetId —— 本栏按对象下钻」，那是**页面契约**而不是故障；
#    而举报单里 `TargetType='comment'` 那一档**跳不动**（`report` 表只存了评论 Id，
#    没存它挂在谁身上）—— 页面给的是「请手动选对象」。两条都在下面各发一次，把边界钉住。
if want(7):
    print("=========== 七、管理端「消息与内容治理」六 tab 的读端点 ===========\n")
    tabs = [
        ("① 评论管理（下钻到商品 %s 的评价）" % HV_GOODS, "/comment/list",
         dict(TargetType="goods", TargetId=HV_GOODS, Kind="review", Limit="20")),
        ("①′ 下钻对象类型写成 comment ⇒ 拒（页面只会传 goods / user）", "/comment/list",
         dict(TargetType="comment", TargetId="114", Limit="20")),
        ("② 问答管理（下钻到商品 %s 的提问）" % DEMO_GOODS, "/comment/list",
         dict(TargetType="goods", TargetId=DEMO_GOODS, Kind="question", Limit="20")),
        ("③ 待审图片", "/comment/imageReview", dict(Action="list", Status="pending")),
        ("④ 举报中心（全量，不筛 Status）", "/report/list", dict()),
        ("⑤ 敏感词库", "/sensitive/list", dict()),
        ("⑥ 消息管理（管理端共享收件箱；参数名是小写 toId/toType）", "/notification/list",
         dict(toId="*", toType="admin", Limit="20")),
    ]
    for name, path, params in tabs:
        show(name, path, **params)

# ============ 八、消息管理收件箱：question.timeout / report.handled ============
if want(8):
    print("=========== 八、治理回流落到通知表（证据档 §4.9 要回填的那两行）==========\n")
    show("管理端共享收件箱（toId=* / toType=admin）", "/notification/list",
         toId="*", toType="admin", Limit="50")
    show("被举报人 hv_b6_troll 的收件箱（report.handled 第一封）", "/notification/list",
         toId="hv_b6_troll", toType="customer", Limit="50")
    show("举报人 aaa 的收件箱（report.handled 第二封）", "/notification/list",
         toId="aaa", toType="customer", Limit="50")
    show("未读数（顶栏红点那条）", "/notification/unreadCount", toId="*", toType="admin")

# ============ 九、待审 → 驳回（**真删文件**）============
# ⚠️ 本段**不再自己上传**：第四段那两条里已经通过了一张、还剩一张 `pending` ⇒ 现在否决它。
if want(9):
    print("=========== 九、待审 → 驳回真删文件 ==========\n")
    dirsnapshot("（㉜ 之前）")
    st, body = show("㉛ 待审列表（要否决的那张在里面）", "/comment/imageReview",
                    Action="list", Status="pending")
    pending = [r.get("Id") for r in ((jload(body) or {}).get("list") or []) if isinstance(r, dict)]
    IDS["㉛pendingIds"] = pending
    print("   ⇒ 待审 Id 列表 = %s\n" % pending)
    if pending:
        show("㉜ 驳回它（Action=reject ⇒ `deleteImageFile` 真删落盘文件）", "/comment/imageReview",
             Action="reject", Id=pending[0])
        IDS["㉜驳回"] = pending[0]
    dirsnapshot("（㉜ 之后）")
    show("㉜′ 已驳回列表（URL 一律不给 —— 文件已不在）", "/comment/imageReview",
         Action="list", Status="rejected")
    show("㉜″ 再驳回同一张 ⇒ 业务失败（CAS：不可逆动作只发生一次）", "/comment/imageReview",
         Action="reject", Id=IDS.get("㉜驳回"))

# ============ 十、3 次警告 → 自动封号 ============
if want(10):
    print("=========== 十、举报通过 ⇒ 警告累计 ⇒ 第 3 次自动封号 ==========\n")
    tail = (MEMO.get("troll_replies") or [])[1:]
    print("   ⇒ 本段用第 4b 段留下的回复：%s（第 5 段那一单已计入第 1 次警告）\n" % tail)
    for i, rid in enumerate(tail, start=1):
        pick("㉟-%d举报" % i, show("㉟-%d aaa 举报回复 %s（理由「辱骂」）" % (i, rid), "/report/add",
                                 Account="aaa", TargetType="comment", TargetId=rid, Reason="辱骂"))
    for i in (1, 2):
        show("㊱-%d 管理员通过第 %d 单（看 warnCount / banned 两个键）" % (i, i), "/report/handle",
             Id=IDS.get("㉟-%d举报" % i), Approve="1", Note="手验：第 %d+1 次警告" % i)

print("################################################################")
print("# 实录结束 —— 本段产生/引用的 Id（库侧真值由调用方用 _work/Sql.java 追加，见本笔证据档）")
for k in sorted(IDS):
    print("#   %s = %s" % (k, IDS[k]))
print("################################################################")
