package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.upload.UploadStorage;
import com.minke.mallmanagement.user.dao.UserDao;
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

    private final UserDao userDao;
    private final UserCacheService userCacheService;
    private final String uploadDir;

    public UserService(UserDao userDao,
                       UserCacheService userCacheService,
                       @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.userDao = userDao;
        this.userCacheService = userCacheService;
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
     * <p>⚠️ {@code System.out.println} 是三层前就有的副作用，照搬 —— 它不是调试残留，
     * 而是 A3 排障时读 {@code backend} job 日志的线索之一。
     */
    public int loginSelect(String username, String password) {
        System.out.println(username + "," + password);

        try {
            Integer role = userDao.selectRoleByCredentials(username, password);
            return role != null ? role : 0;
        } catch (Exception e) {
            // 用户不存在或查询异常 → 返回 0
            return 0;
        }
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
     *             2 = 商家 → {@code merchantid}）；其它值返回 {@code null}
     */
    public String businessId(String username, int role) {
        if (role != 1 && role != 2) {
            return null;
        }
        try {
            Map<String, Object> row = userDao.selectUserByUsername(username);
            if (row == null) {
                return null;
            }
            Object businessId = (role == 1) ? row.get("customerid") : row.get("merchantid");
            return (businessId == null) ? null : businessId.toString();
        } catch (Exception e) {
            // 用户行不存在 / 数据缺失 → 本次登录不写会话（调用方据此跳过）
            return null;
        }
    }

    /**
     * 会话所指向用户的**公开信息**（鉴权笔 2 新增）—— {@code /user/me} 的读路径。
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
     * 取用户信息（前端各页面的用户资料）。
     *
     * <p>三步，每步失败都返回 {@code null}（三层前如此）：① 账号密码校验 → ② {@code SELECT *}
     * → ③ 按 {@code usertype} 补一个 {@code image} 字段。
     *
     * <p>⚠️ <b>第 ③ 步的「补」是无条件的 put</b>：只要 {@code usertype} 命中客户或商家，
     * 就把查询结果（<b>可能是 {@code null}</b>）放进 {@code userInfo.image}。即「查到了但值为
     * NULL」与「查不到」在这里**不可区分**，都表现为 {@code "image": null}；只有
     * 「{@code usertype} 不是这两个」才整个 key 缺席。写回去时别把
     * {@code put(image)} 改成 {@code if (image != null) put(image)} —— 那是 JSON
     * {@code {"image":null}} 与 {@code {}} 的区别，前端 {@code UserInfoPage.vue:96} 的取值路径会变。
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

        try {
            userDao.updateUserProfile(email, address, selfintroduction, username);
            result.put("success", true);
            result.put("msg", "个人信息保存成功");
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "保存失败，请检查数据");
        }

        return result;
    }
}
