package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.text.DataMasking;
import com.minke.mallmanagement.shared.text.MaskedInput;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * user 域的服务层（三层重构第三笔：user 域）。**业务规则 + 上传编排 + 响应组装**；
 * 路由与入参绑定留在 {@link com.minke.mallmanagement.user.controller.UserController}，
 * 数据访问全在 {@link UserDao}。
 *
 * <h2>【迁自哪里 —— 逐字搬运，不是重写】</h2>
 * 5 个方法体整段搬自三层前的 {@code UserController}，含 SQL 调用顺序、try-catch 的**位置与范围**、
 * 分支、以及**每一条文案**（{@code "0"} / {@code "添加成功！"} / {@code "请选择图片"} /
 * {@code "头像保存失败"} / {@code "用户不存在"} / {@code "角色错误"} / {@code "个人信息保存成功"} /
 * {@code "保存失败，请检查数据"}）。搬运的验收判据是「响应逐字段与改前一致」，其中
 * {@code loginAdd} 的 {@code "0"} 与 {@code "添加成功！"} 是 A3 {@code A-B2-1} / {@code B-2} 的判据字符串
 * —— 不是可以顺手润色的东西。
 *
 * <p>方法名 = 端点名（同 {@code GoodsService} / {@code OrderService} 的约定），
 * 使「Controller 一行委托」的对应关系可以肉眼核对。
 *
 * <h2>⚠️【{@code loginAdd} 上的 {@code @Transactional} 必须留在本类】</h2>
 * 它随方法体从 {@code UserController:57} 搬到本例 —— <b>不是新增</b>（搬到本例时，全仓真注解仍是 1 处）。
 * ⚠️ **但那个计数早已变了**：2026-10-04 的 **C#2**（`fix/goods-write-tx`）给 {@code GoodsService}
 * 的三个写方法各加了一处真注解 ⇒ **全仓真注解现为 4 处**（{@code three-layers.md §D.1}、
 * {@code selection-status.md §2} 已同步）。**本类这一处不受影响** —— 它仍是 user 域唯一的事务边界，
 * 只是换了一个宿主类而已。事务边界 = <b>一次业务操作</b>（一次注册的三条 INSERT 要么全成、要么全不成）。
 *
 * <p>⚠️ <b>绝不加 try/catch</b> —— 这一条有两个独立理由，别只记住一个：
 * <ol>
 *   <li><b>语义</b>：三层前就没有 try/catch，INSERT 抛异常时经异常退出、由 {@code @Transactional}
 *       回滚。一旦包上 catch 并返回字符串，异常在事务边界<b>之内</b>被吃掉 ⇒ 事务<b>正常提交</b>，
 *       半截注册（{@code user} 有行、{@code login} 没行）从此可能落库。</li>
 *   <li><b>门禁</b>：A3 的 {@code A-B2-1} <b>期望</b>这条路径是坏的
 *       （{@code EXPECTED_DEFECT_PRESENT}，即 B-2 的 TOCTOU 竞态：查重与 INSERT 之间没有锁）。
 *       加 try/catch 会把「坏」修成「看不见的坏」，门禁从 PASS 变 FAIL。</li>
 * </ol>
 *
 * <h2>⚠️【{@code OrderController} 那条 {@code @Transactional} 禁令**不适用**本类】</h2>
 * 禁令（{@code three-layers.md §D.1}）的前提是「方法体是传给 {@code DistributedLockService.runLocked}
 * 的临界区」—— 锁外提交会让后进者读不到前行。user 域**全程无锁**（A3 的 user 段只打读接口
 * 与并发注册，不依赖 {@code runLocked}）⇒ 本类不受它约束。反面同样成立：
 * 不要把订单域的禁令**外推**到这里来删掉这个注解。
 *
 * <h2>【谁保护它】</h2>
 * <ul>
 *   <li>{@code MallManagementApplicationTests.userControllerIsNotAopProxied} —— 本笔新增。
 *       三层前 {@code UserController} <b>是</b>被代理的（advisor 链含 {@code TransactionInterceptor}），
 *       注解下沉后 Controller 恢复无代理；这条断言把这个翻转钉住。</li>
 *   <li>A3 {@code A-B2-1} —— 钉住「{@code loginAdd} 的查重-插入竞态仍然存在、且没有被 try/catch 掩盖」。</li>
 * </ul>
 *
 * <h2>⚠️【{@code updateUserInfo} **不**清 {@code userPublic} 缓存 —— 这是有据的，不是漏了】</h2>
 * 本方法只写 {@code user} 表的 {@code email} / {@code address} / {@code selfintroduction} 三列，
 * 而 {@code userPublic} 缓存的负载是 {@code customerid} / {@code merchantid} / {@code username} /
 * {@code usertype} / {@code gender} 五列（见 {@code UserCacheService} 类 javadoc）——<b>两集合无交集</b>。
 * 对照 {@code loginAdd}：它写 {@code username} / {@code usertype} / {@code customerid} / {@code merchantid}
 * ⇒ 必须 {@code evictPublic}，三层前也确实调了，本笔原样保留。
 * <b>将来往这条 SET 里加列时，先回头看一眼那五列</b>。
 */
@Service
public class UserService {

    /**
     * 登录时「账号已被封禁」的返回值（C3 块 2 用户块）。
     *
     * <p>⚠️ 为什么是 {@code 4} 而不是一个负数 / 特殊字符串：本方法的返回类型是裸 {@code int}，
     * 取值域原本是 {@code login.Role} 的 {@code 0/1/2/3}（0 = 查不到），而后端把它
     * **原样当字符串写进响应体**（前端 {@code res.text()} 后比对）。扩一个**正整数**是与既有
     * 形状最接近的加法；负数会在前端那些 {@code === "1"} 的比对里变成又一种「没见过的字符串」，
     * 且容易与人的直觉（「负数=错误」）混。⚠️ 它**不是**角色号 ⇒ 任何 `role >= 1` 之类的
     * 范围判断都不能拿本方法的结果直接套。
     */
    public static final int LOGIN_BANNED = 4;

    /**
     * 手机号登录的**演示短信验证码**（2026-10-31「上线前扫雷」）。
     *
     * <p>⚠️ 本地演示没有短信通道，用户裁决「输入手机号 + 获取假验证码 123456 登录」。
     * 本常量**只比对这一个固定值**，不签发、不存储、不按手机号区分 ——
     * 它把「手机号登录」从占位接到真接口（真查库、真写会话、真铸票），
     * 但**不是**一条生产可用的短信认证链（真通道 + 一次性码 + 频控属于另一笔）。
     * 生产环境应把 {@code app.auth.phone.demo-sms-code} 置空以关闭本路径。
     */
    public static final String DEMO_SMS_CODE = "123456";

    /** 手机号登录的判定结果：{@code username} 只在成功（role=1/2）时有值。 */
    public record PhoneLoginResult(String username, int role) {
        static PhoneLoginResult fail() {
            return new PhoneLoginResult(null, 0);
        }
    }

    private final UserDao userDao;
    private final UserCacheService userCacheService;
    private final UserGovernanceDao userGovernanceDao;
    private final UserStateLookup userStateLookup;
    private final String uploadDir;

    public UserService(UserDao userDao,
                       UserCacheService userCacheService,
                       UserGovernanceDao userGovernanceDao,
                       ObjectProvider<UserStateLookup> userStateLookupProvider,
                       @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.userDao = userDao;
        this.userCacheService = userCacheService;
        this.userGovernanceDao = userGovernanceDao;
        // ⚠️ ObjectProvider 而非裸依赖 —— 与 WebAuthConfig 同一条切片论证：@WebMvcTest
        // 不扫 @Service，若裸注入，AbstractSmokeTest 那条 @Import(UserService.class) 会在
        // 上下文加载期就因「找不到 UserStateLookup」而失败（237 测试 / 77 errors 的同型事故）。
        this.userStateLookup = userStateLookupProvider.getIfAvailable();
        this.uploadDir = uploadDir;
    }

    /**
     * 登录第一步：按账号密码取 {@code Role}（决定去客户端还是商家端）。
     *
     * <p>⚠️ 方法签名返回 <b>{@code int}</b>（不是 {@code Integer}）—— 三层前如此，A3 的
     * {@code S1} 断言读的就是这个裸数字（{@code ConcurrencyRunner.java:1400}）。
     * {@code role != null ? role : 0} 与 {@code catch → 0} 都是「查不到 = 0」的表达，
     * 三层前写了两道，本笔**两道都留**（去掉任何一道都不改变可观测行为，但会改变与改前的 diff 幅度）。
     *
     * <p>⚠️ 三层前这里有一行 {@code System.out.println(username + "," + password)}（明文凭据落进程日志）。
     * TD-18（2026-10-20）已删除：管理员铸票改走凭据端点后，登录日志不再是排障的唯一线索，而明文口令是确定的泄漏面。
     *
     * <h2>【C3 块 2（用户块）新增第 4 个返回值：{@link #LOGIN_BANNED}】</h2>
     * 账号被封时答 {@code 4}（既不是角色号，也不是「查不到」的 0）。取值域从
     * {@code {0,1,2,3}} 扩成 {@code {0,1,2,3,4}}。前端 {@code LoginPage.vue} 比对的是
     * **文本**（{@code res.text()} 后比 {@code "0"/"1"/"2"}）。⚠️ 原下一行写的「未识别 4 的旧前端会走
     * 『其它 ⇒ 登录异常』的兜底」**是错的**（2026-10-09 核实）：链无 else、且在 `/user/me` 之后 ⇒ 401 先抛；已在 `:300` 补早退。
     *
     * <p>⚠️ <b>封禁判据必须放在「凭据已验证」之后</b>（本方法就是这么写的）：{@code selectRoleByCredentials}
     * 返回非空才去查 {@code banned}。放到凭据校验之前，等于给出一条**不需要密码**的探测通道
     * —— 谁都能拿一个用户名问出「这个号被封了吗」。
     *
     * <p>⚠️ 封禁闸与「未登录请求闸」是**双查**（裁决 3）：这里管**登录那一刻**，
     * {@code AuthInterceptor} 管**已经握着会话/token 的后续请求**。两处都查、各管一段，
     * 缺任何一处都有明确缺口（只查前者 ⇒ 封禁前已登录的会话能一直用到自然失效；
     * 只查后者 ⇒ 被封的人仍能靠重新登录拿到新鲜会话）。
     */
    public int loginSelect(String username, String password) {

        try {
            Integer role = userDao.selectRoleByCredentials(username, password);
            if (role == null) {
                return 0;
            }
            if (role == 3 && AdminPasswordPolicy.isWeak(password)) return 0;   // TD-18：默认弱口令不签发任何凭据（管理员）
            if (isBanned(username)) {
                return LOGIN_BANNED;
            }
            return role;
        } catch (Exception e) {
            // 用户不存在或查询异常 → 返回 0
            return 0;
        }
    }

    /**
     * 手机号登录（2026-10-31「上线前扫雷」）—— 与 {@link #loginSelect} 同一套出口语义。
     *
     * <p>四道闸，顺序与 {@link #loginSelect} 一致（**短信码在查库之前**，否则给出一条
     * 「拿手机号试库里有没有」的探测通道）：
     * <ol>
     *   <li>手机号必须是 11 位数字、短信码等于 {@link #DEMO_SMS_CODE}，否则 {@code role=0}；</li>
     *   <li>{@code user.phone} 查不到（或同一手机号对应多行）⇒ {@code role=0}；</li>
     *   <li>只放行客户（1）/ 商家（2）—— 管理员走口令端点（{@code /user/adminToken}），
     *       不在这里开一条绕过弱口令闸的路；</li>
     *   <li>封禁 ⇒ {@link #LOGIN_BANNED}，判据与 {@link #loginSelect} 同源（{@code isBanned}）。</li>
     * </ol>
     *
     * <p>⚠️ 与 {@code loginSelect} 一样**只在成功时**由 Controller 写会话 + 由前端铸票；
     * 本方法自身不碰会话、不碰 token。
     */
    public PhoneLoginResult loginByPhone(String phone, String smsCode) {
        if (phone == null || !phone.trim().matches("\\d{11}")) {
            return PhoneLoginResult.fail();
        }
        if (smsCode == null || !DEMO_SMS_CODE.equals(smsCode.trim())) {
            return PhoneLoginResult.fail();
        }
        try {
            Map<String, Object> row = userDao.selectLoginByPhone(phone.trim());
            if (row == null || row.get("Role") == null || row.get("username") == null) {
                return PhoneLoginResult.fail();
            }
            int role = ((Number) row.get("Role")).intValue();
            if (role != 1 && role != 2) {
                return PhoneLoginResult.fail();
            }
            String username = row.get("username").toString();
            if (isBanned(username)) {
                return new PhoneLoginResult(null, LOGIN_BANNED);
            }
            return new PhoneLoginResult(username, role);
        } catch (Exception e) {
            // 查不到 / 多行 / 库不可用 —— 对调用方都是「这次登录不成立」，与 loginSelect 的 →0 同形
            return PhoneLoginResult.fail();
        }
    }

    /**
     * 该账号是否被封。{@code userStateLookup == null}（未装配的切片）⇒ 未封。
     *
     * <p>⚠️ 它在 {@link #loginSelect} 的 {@code try} **之内**是刻意的：查封禁列会碰库，
     * 而「库这时不可用」应该与「查凭据时库不可用」走同一条出口（{@code → 0}），
     * 而不是把登录掀成 500。代价是「库挂了 ⇒ 封禁闸也失效」—— 可接受：那本来就是降级态，
     * 且库挂时凭据校验同样过不去，登录根本走不到这一步。
     */
    private boolean isBanned(String username) {
        return userStateLookup != null && userStateLookup.isBannedByUsername(username);
    }

    /**
     * 登录成功后的第二跳：把**账号**翻成**业务号**（鉴权笔 2 新增）。
     *
     * <p>{@code login} 表只存 {@code Role}，不存业务号；业务号在 {@code user} 表
     * （{@code customerid} / {@code merchantid}）。故登录成功后要再查一次 {@code user}
     * 才能拿到写进会话的那个值。查的是**既有**的 {@link UserDao#selectUserByUsername}
     * （{@code SELECT *}），**不新增 SQL 字面量** —— 全仓「硬编码 schema 前缀 39 处」
     * 是被多处文档引用的基线（TD-13），新增一条就变 40。
     *
     * <p>⚠️ <b>代价（已登记 {@code progress.md §5-Q}）</b>：每次**成功**登录多一次
     * {@code SELECT *}。A3 的 S1 每轮约 1800 次 {@code loginSelect}（= 120 qps × 30 s × 50%），
     * 若它们都是有效凭据，就是每轮多约 1800 次单行读。**失败的登录不查**（见下）。
     *
     * <p>⚠️ <b>失败一律返回 {@code null}，且不抛</b>：查不到（{@code singleRow()} 抛
     * {@code EmptyResultDataAccessException}）、业务号列为 NULL、role 不是 1/2 —— 三种情形
     * 对调用方是同一件事「这次登录没有可写入会话的身份」，形态与 {@link #loginSelect} 的
     * 「查不到 = 0」一致。⚠️ 这**不是**在掩盖技术异常：{@code loginSelect} 那一步已经证明
     * 凭据有效、库是通的；此处查不到只可能是数据本身缺失（{@code user} 行没建全）。
     *
     * @param role {@link #loginSelect} 的返回值（1 = 客户 → {@code customerid}；
     *             2 = 商家 → {@code merchantid}；3 = 管理员 → {@code customerid}）；其它值返回 {@code null}
     */
    public String businessId(String username, int role) {
        if (role != 1 && role != 2 && role != 3) {   // C3（2026-10-08）：管理员并入。⚠️ 同一行改（本文件最大被引行 `:196`，插行必漂移）
            return null;
        }
        try {
            Map<String, Object> row = userDao.selectUserByUsername(username);
            if (row == null) {
                return null;
            }
            Object businessId = (role == 2) ? row.get("merchantid") : row.get("customerid");   // C3（2026-10-08）：判据由 `(role == 1)` 改为 `(role == 2)` —— 客户(1)与管理员(3)同取 `customerid`。⚠️ role=1/2 的取值与改前**逐字相同**（1→customerid、2→merchantid），只是把「非商家即客户」写成正面判据。管理员行借 `customerid` 存 `login.Id`，理由见 slot-registry §十三 13-3
            return (businessId == null) ? null : businessId.toString();
        } catch (Exception e) {
            // 用户行不存在 / 数据缺失 → 本次登录不写会话（调用方据此跳过）
            return null;
        }
    }

    /**
     * 会话所指向用户的**公开信息**（鉴权笔 2 新增）—— P2.6（2026-10-05）起是 {@link #meInfo} 的**第一跳**（{@code /user/me} 已改为答扩列后的完整资料）。
     *
     * <p>纯转调 {@link UserCacheService#publicInfo(String)}，本方法**不加工**：缓存的
     * 两级、负缓存（0 行 → {@code null}）、以及「技术异常原样上抛」——三条语义全在那一层，
     * 在这里复述一遍只会多出一个可能与之分歧的地方。
     *
     * <p>为什么要经过本类而不是让 {@code UserController} 直接注入 {@code UserCacheService}：
     * 本类**已经持有**它（{@code loginAdd} 的失效面），多注入一处等于给 Controller
     * 增加一个依赖面，而这里只需一行委托。
     *
     * <p>⚠️ 键是**业务号**（不是登录名）—— 与 {@link #businessId} 的产出同域，
     * 见 {@link com.minke.mallmanagement.shared.web.AuthSession} 类注释。
     */
    public Map<String, Object> publicInfo(String userId) {
        return userCacheService.publicInfo(userId);
    }

    /**
     * 注册（最早期版本：只写最基础的用户信息）。
     *
     * <p>三步：查重 → 按 {@code usertype} 分客户 / 商家两支各写三张表 → 清缓存。
     * 返回字符串：{@code "0"} 表示用户名已存在，{@code "添加成功！"} 表示成功。
     *
     * <p>⚠️ 见类注释：{@code @Transactional} 在此、且**绝不加 try/catch**。
     *
     * <p>⚠️ <b>{@code customeridAdd = id;} / {@code merchantidAdd = id;} 这两行留在本类</b>
     * —— 它们是业务规则（「客户/商家的业务主键就是注册时传的 userid」），不是 SQL 的一部分，
     * 故不搬进 {@link UserDao}。DAO 收到的是已决定的列值。
     *
     * <p>⚠️ <b>既有缝隙（本笔照搬，不修）</b>：{@code usertype} 既不是「客户」也不是「商家」时，
     * 两个分支都不进 ⇒ <b>一行都不写</b>，却仍然走到 {@code evictPublic} 并返回
     * {@code "添加成功！"} —— 即「什么都没做，但报成功」。三层前就是这样，
     * 本笔只搬运；修它属另一笔（并会动 A3 的 B-2 判据）。
     */
    @Transactional
    public String loginAdd(String imageAdd, String usernameAdd, String usertypeAdd, String id,
                           String customeridAdd, String merchantidAdd,
                           String password, Integer ageAdd, String genderAdd) {
        // 检查用户是否已存在
        if (userDao.existsUserName(usernameAdd)) {
            return "0";  // 用户已存在，返回0
        }
        int role;
        // 根据用户类型处理
        if ("客户".equals(usertypeAdd)) {
            // 插入 user 基础表
            customeridAdd = id;
            userDao.insertUserCustomer(usernameAdd, usertypeAdd, customeridAdd, genderAdd, ageAdd);
            // 插入客户头像表
            userDao.insertCustomerImage(usernameAdd, customeridAdd, imageAdd);
            role = 1;
            // 插入登录表
            userDao.insertLoginCustomer(usernameAdd, password, role, customeridAdd);
        } else if ("商家".equals(usertypeAdd)) {
            merchantidAdd = id;
            userDao.insertUserMerchant(usernameAdd, usertypeAdd, merchantidAdd, genderAdd, ageAdd);
            userDao.insertMerchantImage(usernameAdd, merchantidAdd, imageAdd);
            role = 2;
            userDao.insertLoginMerchant(usernameAdd, password, role, merchantidAdd);
        }
        // ⚠️ 本方法的唯一出口 —— 若新增 return 请确认：
        //    ① 该分支不改库，或
        //    ② 在该分支前也调 userCacheService.evictPublic(id)
        // 现有两条 return：上面「用户已存在」那条只跑了 SELECT（未改 user）；本方法无 try/catch，
        // INSERT 抛异常时经异常退出、而 @Transactional 会回滚 ⇒ 异常路径也不改库
        // ⇒ 唯一出口覆盖 100% 的可能改库路径。
        // 清的是 userPublic::<id> 的**正条目与负条目两者**：新注册的 id 此前若被查过，
        // 缓存里可能是「不存在」的负条目，不清则本用户到 TTL 前都读不到自己。
        userCacheService.evictPublic(id);
        return "添加成功！";
    }

    /**
     * 取用户信息（前端各页面的用户资料）—— **凭据驱动**那一半的残留（P2.6 起前端已无调用方）。
     *
     * <p>两步：① 账号密码校验 → ② {@link #profileOf}（与 {@code /user/me} **共用同一份实现**）。
     *
     * <p>⚠️ <b>P2.6（2026-10-05）后本端点今日**没有**前端调用方</b>（三个调用点全改走会话驱动的
     * {@code /user/me}），但**刻意保留**：删它会连带删掉 {@link UserDao#selectRoleForProfile}
     * 那条 SQL ⇒ 全仓「硬编码 schema 前缀 39 处」的基线当场变 38、端点计数 35 变 34。
     * 归属另一笔（「跨四域清除 schema 前缀」或单独记账笔），见 progress.md §5-AC。
     *
     * <p>⚠️ 返回值**可能为 {@code null}** —— Controller 原样透传，Spring 会把它序列化成空响应体
     * （不是 {@code "null"}）。三层前如此，不改。
     */
    public Map<String, Object> getUserInfo(String username, String password) {
        // 1. 验证账号密码
        // ⚠️ 这里走 selectRoleForProfile 而不是 selectRoleByCredentials —— 两者 SQL 文本相同，
        //    拆两个方法是刻意的（保住全仓「39 处硬编码前缀」的计数口径）。见 UserDao 类注释。
        try {
            Integer role = userDao.selectRoleForProfile(username, password);
            if (role == null) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        return profileOf(username);
    }

    /**
     * 用户资料 = {@code user} 表整行 + 按 {@code usertype} 补的 {@code image}
     * （P2.6，2026-10-05 抽自 {@link #getUserInfo} 的第 ②③ 步，**逐字搬运**）。
     *
     * <p>为什么要抽：{@code /user/me}（会话驱动）与 {@code /user/getUserInfo}（凭据驱动）
     * <b>必须返回同一张 Map</b> —— 这是前端三个调用点能「只换地址、不改模板」的依据
     * （验收断言见 {@code AuthSessionSmokeTest}）。
     *
     * <p>⚠️ <b>第 ③ 步的「补」是无条件的 put</b>：只要 {@code usertype} 命中客户或商家，
     * 就把查询结果（<b>可能是 {@code null}</b>）放进 {@code userInfo.image}。即「查到了但值为
     * NULL」与「查不到」在这里**不可区分**，都表现为 {@code "image": null}；只有
     * 「{@code usertype} 不是这两个」才整个 key 缺席。写回去时别把
     * {@code put(image)} 改成 {@code if (image != null) put(image)} —— 那是 JSON
     * {@code {"image":null}} 与 {@code {}} 的区别，前端取值路径会变。
     *
     * <p>⚠️ <b>两步的失败面照搬 {@code getUserInfo} 的旧形状</b>：② 抛异常 ⇒ 返回 {@code null}；
     * ③ 抛异常 ⇒ 只跳过 {@code image}（不改动已查到的行）。
     *
     * <p>⚠️ <b>别把这里和 {@link #updateAvatar} 的选表合并</b>：本方法按 {@code user.usertype}
     * （中文）选表，{@code updateAvatar} 按 {@code login.Role}（数字）选表 —— 两条判据不同源。
     *
     * @param username 登录名（{@code user} 表主键；不是业务号 —— 业务号 → 用户名那一跳在 {@link #meInfo}）
     */
    private Map<String, Object> profileOf(String username) {
        // 2. 查询 user 表全部字段
        Map<String, Object> userInfo;
        try {
            userInfo = userDao.selectUserByUsername(username);
        } catch (Exception e) {
            return null;
        }

        // 3. 根据 usertype（中文：商家或客户）查询对应的图片地址
        Object userTypeObj = userInfo.get("usertype"); // 假设字段名就是 usertype
        if (userTypeObj != null) {
            String userType = userTypeObj.toString();
            boolean isCustomer = "客户".equals(userType);
            boolean isMerchant = "商家".equals(userType);

            if (isCustomer || isMerchant) {
                try {
                    String image = isCustomer
                            ? userDao.selectCustomerImage(username)
                            : userDao.selectMerchantImage(username);
                    userInfo.put("image", image);
                } catch (Exception e) {
                    // 无图片或查询失败，不添加 image 字段（或者可以添加 null）
                    // userInfo.put("image", null);
                }
            }
        }

        return userInfo;
    }

    /**
     * 会话所指向用户的**完整资料**（P2.6，2026-10-05）—— 扩列后 {@code /user/me} 的读路径。
     *
     * <p><b>两跳（本笔的核心设计，别改成「新写一条按业务号查整行的 SQL」）</b>：
     * <ol>
     *   <li><b>业务号 → 公开信息</b>：{@link #publicInfo}（走既有的 {@code userPublic} 缓存 +
     *       负缓存）取 {@code username}。复用它的收益是**零新增 SQL 字面量** —— 新写一条带
     *       {@code mall_management_sql.} 前缀的语句会把全仓基线 39 顶成 40。</li>
     *   <li><b>用户名 → 资料行 + image</b>：{@link #profileOf}，与 {@code getUserInfo} 共用。</li>
     * </ol>
     *
     * <p>⇒ 与 {@link #getUserInfo} 的唯一差别是<b>没有凭据校验</b>（身份来自会话）：
     * 两者对同一用户返回**同一张 Map**（只差 Controller 包装的 {@code success} 键）。
     *
     * <p>⚠️ <b>为什么**不**加宽 {@code userPublic} 缓存</b>（本笔裁决 D1）：那条缓存被
     * {@code /message/userSelect} 共用，且 {@link #updateUserInfo} 的「不清缓存」论证
     * （本类类注释）正建立在「缓存负载 = 5 列、与 SET 的三列无交集」之上 —— 加宽会让那条
     * 不变量当场作废。故本方法**只把缓存当跳板**，扩出来的 5 列每次实读、不进缓存
     * ⇒ {@code updateUserInfo} / {@code updateAvatar} 都**不需要**新增失效点。
     *
     * @param userId 会话里的**业务号**（{@code customerid} / {@code merchantid}，见
     *               {@link com.minke.mallmanagement.shared.web.AuthSession}）
     * @return 查无此人 ⇒ {@code null}（Controller 翻成 200 + {@code {success:false}}）
     */
    public Map<String, Object> meInfo(String userId) {
        Map<String, Object> publicRow = publicInfo(userId);
        if (publicRow == null) {
            return null;
        }
        Object username = publicRow.get("username");
        return (username == null) ? null : profileOf(username.toString());
    }

    /**
     * 更改头像：落盘 → 查角色 → 按角色更新对应头像表。
     *
     * <p>三条早退（图片为空 / 落盘失败 / 用户不存在 / 角色错误）各自带着自己的文案，
     * 与 {@code GoodsService} 的上传编排同形；<b>文案逐字未改</b>。
     *
     * <p>⚠️ 落盘失败这条早退是 2026-09-30 修过的（{@code TD-07}）：原先「就算保存失败也不崩溃」，
     * 吞掉异常继续写库 ⇒ DB 记下指向不存在文件的 URL，前端却弹「成功」。本笔原样保留修复后的写法。
     *
     * <p>⚠️ {@code Paths.get(uploadDir)} 这行在<b>早退之前</b> —— 与三层前同位置（同
     * {@code GoodsService.goodsAdd}）。它只是构造一个 Path 对象、不做 IO，故提前无副作用。
     *
     * <p>⚠️ {@code role == 1} 的拆箱：{@code Role} 列为 NULL 时会 NPE，且本方法**不捕获它**
     * （三层前也不捕获）—— 这是既有形状，照搬。捕获它会把它变成 {@code "角色错误"}，
     * 属行为变更。
     *
     * <p>⚠️ {@code throws IOException} 是三层前的签名遗物：{@code UploadStorage.store} 声明的
     * 受检异常已被上面的 {@code catch (Exception e)} 吃尽，实际永不抛出。本笔保留它，
     * 是为了让 Controller 的签名与三层前逐字一致（便于 diff 核对）。
     */
    public Map<String, Object> updateAvatar(String username, MultipartFile file) throws IOException {

        Map<String, Object> result = new HashMap<>();

        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();

        if (file.isEmpty()) {
            result.put("success", false);
            result.put("msg", "请选择图片");
            return result;
        }

        String originalFilename;
        try {
            originalFilename = UploadStorage.store(uploadPath, file);
        } catch (Exception e) {
            // 显式失败。原先这里是「就算保存失败，也不崩溃！」→ 吞掉异常继续写库，
            // DB 记下一个指向不存在文件的 URL，前端却弹「成功」。
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "头像保存失败");
            return result;
        }

        String imageUrl = "http://localhost:8088/img/" + originalFilename;

        // 查询角色
        Integer role;
        try {
            role = userDao.selectRoleByUserName(username);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            e.printStackTrace();
            return result;
        }

        // 更新头像
        if (role == 1) {
            userDao.updateCustomerImage(imageUrl, username);
        } else if (role == 2) {
            userDao.updateMerchantImage(imageUrl, username);
        } else {
            result.put("success", false);
            result.put("msg", "角色错误");
            return result;
        }

        result.put("success", true);
        result.put("imageUrl", imageUrl);
        return result;
    }

    /**
     * 更新个人信息（{@code email} / {@code address} / {@code selfintroduction}）。
     *
     * <p>⚠️ <b>不</b>清 {@code userPublic} 缓存 —— 见类注释「有据的，不是漏了」。
     *
     * <p>⚠️ 成功文案 {@code "个人信息保存成功"} 与失败文案 {@code "保存失败，请检查数据"} 逐字未改；
     * {@code e.printStackTrace()} 的位置（{@code success=false} 之前）也未改。
     */
    public Map<String, Object> updateUserInfo(String username, String email,
                                              String address, String selfintroduction) {
        Map<String, Object> result = new HashMap<>();

        // 2026-10-31「上线前扫雷」：/user/me 与 /user/getUserInfo 起答**掩码**，
        // 而资料页的编辑框直接绑在这份响应上 ⇒ 用户只改地址、原样提交时会把
        // 「a***@qq.com」写进库。这里的判据是「回显」：入参恰等于**当前值的掩码**
        // ⇒ 视为「这一格没改」，用真值替换；其余带 `*` 的入参仍是非法值（同
        // MaskedInput 的写侧拦截，只是多了这一条合法回显的例外）。
        Map<String, Object> current;
        try {
            current = userDao.selectUserByUsername(username);
        } catch (Exception e) {
            current = null;   // 行不存在：没有可比对的真值 ⇒ 任何带 `*` 的值都按非法处理
        }
        String emailValue = email;
        if (email != null && email.indexOf('*') >= 0) {
            Object raw = (current == null) ? null : current.get("email");
            if (raw instanceof String s && email.equals(DataMasking.maskEmail(s))) {
                emailValue = s;
            } else {
                return rejected(result, "邮箱");
            }
        }
        String addressValue = address;
        if (address != null && address.indexOf('*') >= 0) {
            Object raw = (current == null) ? null : current.get("address");
            if (raw instanceof String s && address.equals(DataMasking.maskAddress(s))) {
                addressValue = s;
            } else {
                return rejected(result, "地址");
            }
        }

        try {
            userDao.updateUserProfile(emailValue, addressValue, selfintroduction, username);
            result.put("success", true);
            result.put("msg", "个人信息保存成功");
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "保存失败，请检查数据");
        }

        return result;
    }

    /** 掩码值不是本行当前值的回显 ⇒ 明确拒绝，别把 `*` 写进库（复用 {@link MaskedInput} 的文案）。 */
    private static Map<String, Object> rejected(Map<String, Object> result, String field) {
        result.put("success", false);
        result.put("msg", MaskedInput.rejectMessage(field));
        return result;
    }

    /**
     * 更新可编辑资料（{@code gender} / {@code age}）—— C3 块 2（用户块）「资料完善」的写口。
     *
     * <h2>【⚠️ 与 {@link #updateUserInfo} 的关键差别：这个**必须**清 {@code userPublic} 缓存】</h2>
     * {@code updateUserInfo} 写的是 {@code email} / {@code address} / {@code selfintroduction}，
     * 与本类类注释里那条不变量（缓存负载 = {@code customerid} / {@code merchantid} / {@code username}
     * / {@code usertype} / {@code gender} 五列）**无交集** ⇒ 免清。
     * 本方法写的 {@code gender} <b>正在那五列里</b> ⇒ 不清就会「资料页刚改完、回首页看还是旧的」，
     * 一直到 TTL 到期。⚠️ <b>两条写路径的失效面结论相反，别照抄对方那句注释。</b>
     *
     * <p>⚠️ 清**两个**键（{@code customerid} 与 {@code merchantid}，各自非空才清）：
     * 块 2 之后一个升级过的商家**两列都有值**（{@code promoteToMerchant} 刻意保留
     * {@code customerid}），而会话键随 {@code login.Role} 在两者之间切换 ⇒ 只清一个必然漏一个。
     * ⚠️ {@code evictPublic(null)} 会以 {@code null} 为键去 {@code RedisCache.evict} ⇒ NPE，
     * 故这里逐个判空。
     *
     * <p>⚠️ {@code gender} 不传（{@code null}）时的语义是「**把它置空**」，不是「保持不变」——
     * 这是 DAO 那条 {@code SET gender = ?, age = ?} 的直接结果，前端要么把当前值带上，
     * 要么接受清空。块 2 不做「部分更新」那种花活（那要求 DAO 侧拼动态 SET）。
     *
     * <p>⚠️ <b>返回值里的 {@code before} / {@code after} 是给 Controller 记审计用的</b>
     * （同 {@code MerchantApplicationService.audit} 把 {@code outcome} 交给 Controller 的手法）：
     * 「该通知谁 / 该记什么」由 Service 决定，而 {@code AuditService.record} 是 fail-soft、无事务的，
     * 必须在业务事务**提交之后**调 ⇒ 只能在 Controller 层发。两张小 Map 只装
     * {@code gender} / {@code age} 两列，**不是整行** —— 整行会把 {@code banned} / {@code banReason}
     * 这些与本次动作无关的列带进 HTTP 响应。
     */
    public Map<String, Object> updateProfile(String username, String gender, Integer age) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> beforeRow = userGovernanceDao.selectUserRow(username);
            if (beforeRow == null) {
                result.put("success", false);
                result.put("msg", "用户不存在");
                return result;
            }
            userGovernanceDao.updateProfile(username, gender, age);
            evictProfileCache(username);

            result.put("success", true);
            result.put("msg", "资料保存成功");
            result.put("before", profileSnapshot(beforeRow.get("gender"), beforeRow.get("age")));
            result.put("after", profileSnapshot(gender, age));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "保存失败，请检查数据");
        }
        return result;
    }

    /** 审计快照 —— 只装 {@code gender} / {@code age}（见 {@link #updateProfile} 的论证）。 */
    private static Map<String, Object> profileSnapshot(Object gender, Object age) {
        Map<String, Object> m = new HashMap<>();
        m.put("gender", gender);
        m.put("age", age);
        return m;
    }

    /** 清该账号名下**两个**可能的 {@code userPublic} 键 —— 见 {@link #updateProfile} 的论证。 */
    private void evictProfileCache(String username) {
        String customerId = userGovernanceDao.selectCustomerId(username);
        String merchantId = userGovernanceDao.selectMerchantId(username);
        if (customerId != null) {
            userCacheService.evictPublic(customerId);
        }
        if (merchantId != null) {
            userCacheService.evictPublic(merchantId);
        }
    }
}
