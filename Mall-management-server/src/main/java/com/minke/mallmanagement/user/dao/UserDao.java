package com.minke.mallmanagement.user.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * user 域的**内联 SQL 唯一出口**（三层重构第三笔：user 域）。
 *
 * <h2>【收什么、不收什么】</h2>
 * 只收三层前**内联在 {@code UserController} 方法体里**的语句 —— 5 个端点的全部 SQL。
 * 本条判据与 {@link com.minke.mallmanagement.goods.dao.GoodsDao} 一致（裁决②）。
 *
 * <p>⚠️ <b>本域是「唯一数据出口」</b>（与订单域 {@link com.minke.mallmanagement.order.dao.OrderDao} 同形，
 * 与商品域**不同**）：本域唯一的另一个数据访问点是
 * {@link com.minke.mallmanagement.user.service.UserCacheService#publicInfo(String)}
 * —— 它服务的是 **{@code /message/userSelect}**，路由与业务都归 message 域，
 * 故那条 SQL **不在本类**（它连 {@code mall_management_sql.} 前缀都没有）。本类覆盖
 * {@code UserController} 的 5 个端点，无遗漏。
 *
 * <h2>【SQL 字面量逐字未变 —— 这是本笔的验收判据，不是修辞】</h2>
 * 共 <b>16 处</b> schema 前缀 = <b>16 处字面量出现</b>（对应三层前 {@code UserController} 的 16 处），
 * 全部**从原方法体整段搬来**。三处必须注意：
 * <ul>
 *   <li>⚠️ <b>{@link #selectRoleByCredentials} 与 {@link #selectRoleForProfile} 的 SQL 文本完全相同</b>
 *       —— 三层前 {@code UserController:42}（{@code loginSelect}）与 {@code :131}（{@code getUserInfo}）
 *       各写了一遍同一条 {@code SELECT Role …}。<b>本笔刻意不合并</b>，保留两份字面量，
 *       理由见类注释「为什么宁可冗余两份 SQL 也不合并」。</li>
 *   <li>{@code mall_management_sql.} 前缀（16 处）—— ⚠️ 本笔**不消灭**它。判据沿用订单笔与商品笔：
 *       {@code three-layers.md §F#1} 说它「抽 DAO 层时自然改完」，但 {@code OrderDao} / {@code GoodsDao}
 *       的裁决都把它判给**单独一笔**（跨四域 39 处，字面量口径，2026-10-02 实测）—— 只清一域会造出
 *       「一半有前缀、一半没有」的中间态。⚠️ **这与 §F#1 的口径是一处已知矛盾，不是疏漏**。</li>
 * </ul>
 *
 * <h2>⚠️【为什么宁可冗余两份 SQL 也不合并 —— 这不是疏漏，是计数口径的直接后果】</h2>
 * 全仓「硬编码 schema 前缀 <b>39 处</b>」（TD-13，2026-10-02 逐处实测；口径 = SQL 字面量出现次数）
 * 是一个**被多处文档引用的验收基线**，本笔的裁决 C 刚把 {@code GoodsDao:35} 从 45 订正为 39、
 * 与 {@code OrderDao:22} 对齐。若本笔把这两条同文本的语句合并成一条，全仓口径立刻变
 * <b>38</b> ⇒ {@code GoodsDao:35} 与 {@code OrderDao:22} 当场重新互相矛盾，
 * 且那是**跨四域的口径变更**（`OrderDao` 不在本笔范围）—— 属于「夹带」。
 *
 * <p>故本笔选择：<b>DAO 按「调用点语义」暴露两个方法</b>（登录判别 / 资料页校验），
 * 二者当前字面相同。合并它们是一件**该做但归属别处**的事 —— 应与
 * 「跨四域清除 schema 前缀」（方案 A）同笔进行，那时 39 → 0，冗余自然消失。
 *
 * <p>⚠️ 换句话说：<b>这里是「冗余两份 SQL」换「39 这个数继续为真」</b>。
 * 谁将来合并了这两个方法，必须同时改 {@code GoodsDao:35} 与 {@code OrderDao:22} 的 39。
 *   <li>⚠️ <b>{@link #insertLoginCustomer} 与 {@link #insertLoginMerchant} 的列名拼写不同</b>：
 *       前者 {@code (UserName, Password, Role, Id)}、后者 {@code (UserName, PassWord, Role, Id)}。
 *       MySQL 列名大小写不敏感 ⇒ **两者等价**，但本笔**照抄不改** —— 理由同商品笔照抄
 *       {@code (MerchantId, GoodName, ProductCode,OrderVolume)} 里那个缺失的空格：保留原字面量
 *       的收益是「逐条与三层前的 Controller 做字符串比对即可，不必重推语义」。统一拼写另笔。</li>
 *   <li>⚠️ {@link #selectUserByUsername} 是 {@code SELECT *}（本类唯一的 {@code *}）——
 *       照搬。它的列集耦合见 {@code UserCacheService} 类 javadoc 与 cache-layer §10 边界第 4 条。</li>
 * </ul>
 *
 * <h2>【为什么是 {@code JdbcClient}】</h2>
 * 与 {@code OrderDao} / {@code GoodsDao} 同款：D#4 已定「短期 {@code JdbcClient}（零新依赖），
 * 长期 jOOQ」。构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**，是为了让
 * {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类
 * —— 切片不加载 Boot 的 {@code JdbcClient} 自动配置。
 *
 * <p>⚠️ <b>这个选择把一处坑传给了 {@code LoginSelectSmokeTest}</b>：{@code JdbcClient} 的
 * {@code query(Class).single()} 落到 {@code JdbcTemplate.query(String, RowMapper, Object...)}，
 * **不是** {@code queryForObject} ⇒ 旧 stub 不再命中。理由与修法见该测试的类 javadoc。
 *
 * <h2>⚠️【本类**可以**加 {@code @Transactional}】（与 {@code OrderDao} 相反）</h2>
 * 订单域的 {@code @Transactional} 禁令（{@code three-layers.md §D.1}）**只管被打锁的临界区**；
 * user 域**无锁** ⇒ 本类不受它约束。但目前**不加** —— 本笔是纯搬运，改事务边界是行为变更。
 * 事务边界由 {@code UserService.loginAdd} 上的 {@code @Transactional} 承担（三层前的原状搬来）。
 */
@Repository
public class UserDao {

    private final JdbcClient jdbcClient;

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 读：登录与角色 ====================

    /**
     * 按账号密码取 {@code Role} —— {@code loginSelect} 用（决定进客户端还是商家端）。
     *
     * <p>⚠️ 0 行时抛 {@code EmptyResultDataAccessException}（{@code .single()} 的语义）——
     * <b>这是调用方依赖的</b>：{@code loginSelect} 的 try/catch 把它翻成 {@code 0}。
     * 换成 {@code optional()} 会把「查不到」变成 {@code Optional.empty()}，catch 就不再触发。
     *
     * <p>⚠️ 与 {@link #selectRoleForProfile} 的 SQL 文本**完全相同**，且**刻意不合并**
     * —— 理由见类注释「为什么宁可冗余两份 SQL 也不合并」。改这条 SQL 时**两条一起改**。
     */
    public Integer selectRoleByCredentials(String username, String password) {
        String sql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ? AND PassWord = ?";
        return jdbcClient.sql(sql).params(username, password).query(Integer.class).single();
    }

    /**
     * 按账号密码取 {@code Role} —— {@code getUserInfo} 用它做第 1 步的账号密码校验。
     *
     * <p>⚠️ 与 {@link #selectRoleByCredentials} 是**同一条语句的两个出现**（三层前
     * {@code UserController:42} 与 {@code :131} 各写一遍）。本笔**刻意保留两份字面量**，
     * 不合并成一条 —— 见类注释。0 行语义同上：抛异常，调用方 catch 后返回 {@code null}。
     */
    public Integer selectRoleForProfile(String username, String password) {
        String sql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ? AND PassWord = ?";
        return jdbcClient.sql(sql).params(username, password).query(Integer.class).single();
    }

    /**
     * 手机号 → 登录名 + 角色（2026-10-31「手机号登录」）。{@code user.phone} 是本次
     * Migration ㉛ 新加的列；0 行抛 {@code EmptyResultDataAccessException}（调用方 catch 成「查不到」）。
     *
     * <p>⚠️ 必须 JOIN {@code login}：手机号落在 {@code user} 表（人），角色只在 {@code login}（凭据表）。
     * 同一手机号可能出现多行（历史数据没有唯一约束时）⇒ {@code singleRow()} 抛
     * {@code IncorrectResultSizeDataAccessException}，与「查不到」同归为登录失败 —— 不猜。
     */
    public Map<String, Object> selectLoginByPhone(String phone) {
        String sql = "SELECT u.username AS username, l.Role AS Role "
                + "FROM mall_management_sql.user u JOIN mall_management_sql.login l ON l.UserName = u.username "
                + "WHERE u.phone = ?";
        return jdbcClient.sql(sql).param(phone).query().singleRow();
    }

    /**
     * 用户名是否已存在（注册查重）。
     *
     * <p>⚠️ <b>这是 §5 #5 的 TOCTOU 竞态点</b>：查重与 INSERT 分属两条语句、无唯一约束兜底，
     * 并发下两个请求可同时通过查重。本笔**只搬运**，不加锁 —— 该竞态由 A3 的
     * {@code A-B2-1} 断言（期望 {@code EXPECTED_DEFECT_PRESENT}）钉住。
     */
    public boolean existsUserName(String username) {
        String sql = "SELECT UserName FROM mall_management_sql.login WHERE UserName = ?";
        return !jdbcClient.sql(sql).param(username).query().listOfRows().isEmpty();
    }

    /** 按用户名取 {@code Role}（{@code updateAvatar} 用来选头像表）。 */
    public Integer selectRoleByUserName(String username) {
        String sql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ?";
        return jdbcClient.sql(sql).param(username).query(Integer.class).single();
    }

    // ==================== 读：用户与头像 ====================

    /**
     * 按用户名取 {@code user} 表的全部列（{@code SELECT *}）。
     *
     * <p>⚠️ 0 行时抛 {@code EmptyResultDataAccessException}、多行时抛
     * {@code IncorrectResultSizeDataAccessException} —— 与三层前的
     * {@code queryForMap} **逐字同语义**（{@code singleRow()} 底层就是
     * {@code JdbcTemplate.queryForMap}）。{@code getUserInfo} 的 catch 依赖它翻成 {@code null}。
     */
    public Map<String, Object> selectUserByUsername(String username) {
        String sql = "SELECT * FROM mall_management_sql.user WHERE username = ?";
        return jdbcClient.sql(sql).param(username).query().singleRow();
    }

    /** 取客户头像。0 行抛异常（调用方 catch 后**不补 image 字段**，是既有形态）。 */
    public String selectCustomerImage(String username) {
        String sql = "SELECT image FROM mall_management_sql.customerimage WHERE username = ?";
        return jdbcClient.sql(sql).param(username).query(String.class).single();
    }

    /** 取商家头像。0 行语义同 {@link #selectCustomerImage}。 */
    public String selectMerchantImage(String username) {
        String sql = "SELECT image FROM mall_management_sql.merchantimage WHERE username = ?";
        return jdbcClient.sql(sql).param(username).query(String.class).single();
    }

    // ==================== 写：注册（{@code loginAdd}，三表） ====================

    /**
     * 注册 · 客户分支：{@code user} 表插一行。
     *
     * <p>⚠️ 参数顺序与 INSERT 的列顺序**逐字对应**
     * （{@code username, usertype, customerid, gender, age}）—— 两处顺序一旦错位，
     * 没有任何断言会报错，只会有行数据静默错位（同 {@code GoodsDao.insertGoods} 的警告）。
     *
     * <p>⚠️ 调用方传进来的 {@code customerid} **就是入参 {@code id}** —— 三层前那一行
     * {@code customeridAdd = id;} 留在 {@code UserService.loginAdd}（它是业务规则，
     * 不是 SQL 的一部分）。本方法只负责「照这个值写这一列」。
     */
    public int insertUserCustomer(String username, String usertype, String customerid,
                                  String gender, Integer age) {
        String sql = "INSERT INTO mall_management_sql.user (username, usertype, customerid, gender, age) "
                + "VALUES (?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql).params(username, usertype, customerid, gender, age).update();
    }

    /** 注册 · 商家分支：{@code user} 表插一行（列清单用 {@code merchantid}）。与客户分支**不可合并** —— 列名不同。 */
    public int insertUserMerchant(String username, String usertype, String merchantid,
                                  String gender, Integer age) {
        String sql = "INSERT INTO mall_management_sql.user (username, usertype, merchantid, gender, age) "
                + "VALUES (?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql).params(username, usertype, merchantid, gender, age).update();
    }

    /** 注册 · 客户分支：{@code customerimage} 表插一行。 */
    public int insertCustomerImage(String username, String customerId, String image) {
        String sql = "INSERT INTO mall_management_sql.customerimage (UserName, CustomerId, image) "
                + "VALUES (?, ?, ?)";
        return jdbcClient.sql(sql).params(username, customerId, image).update();
    }

    /** 注册 · 商家分支：{@code merchantimage} 表插一行。 */
    public int insertMerchantImage(String username, String merchantId, String image) {
        String sql = "INSERT INTO mall_management_sql.merchantimage (UserName, MerchantId, image) "
                + "VALUES (?, ?, ?)";
        return jdbcClient.sql(sql).params(username, merchantId, image).update();
    }

    /**
     * 注册 · 客户分支：{@code login} 表插一行。列名用 {@code Password}。
     *
     * <p>⚠️ 与 {@link #insertLoginMerchant} 是**同一条语句的两种拼写**（{@code Password} / {@code PassWord}）
     * —— 见类注释「SQL 字面量逐字未变」。本笔不合并。
     */
    public int insertLoginCustomer(String username, String password, int role, String id) {
        String sql = "INSERT INTO mall_management_sql.login (UserName, Password, Role, Id) VALUES (?, ?, ?, ?)";
        return jdbcClient.sql(sql).params(username, password, role, id).update();
    }

    /** 注册 · 商家分支：列名用 {@code PassWord}。见 {@link #insertLoginCustomer}。 */
    public int insertLoginMerchant(String username, String password, int role, String id) {
        String sql = "INSERT INTO mall_management_sql.login (UserName, PassWord, Role, Id) VALUES (?, ?, ?, ?)";
        return jdbcClient.sql(sql).params(username, password, role, id).update();
    }

    // ==================== 写：头像与资料 ====================

    /** 换头像 · 客户分支：{@code customerimage} 按用户名更新 {@code image}。 */
    public int updateCustomerImage(String image, String username) {
        String sql = "UPDATE mall_management_sql.customerimage SET image = ? WHERE username = ?";
        return jdbcClient.sql(sql).params(image, username).update();
    }

    /** 换头像 · 商家分支：{@code merchantimage} 按用户名更新 {@code image}。 */
    public int updateMerchantImage(String image, String username) {
        String sql = "UPDATE mall_management_sql.merchantimage SET image = ? WHERE username = ?";
        return jdbcClient.sql(sql).params(image, username).update();
    }

    /**
     * 改资料：{@code user} 表更新 {@code email} / {@code address} / {@code selfintroduction}。
     *
     * <p>⚠️ 这三列与 {@code userPublic} 缓存的 5 列（{@code customerid} / {@code merchantid} /
     * {@code username} / {@code usertype} / {@code gender}）**无交集** ⇒ 本语句**不需要**触发
     * {@code UserCacheService.evictPublic()}。这条不变量写在 {@link com.minke.mallmanagement.user.service.UserService}
     * 的类 javadoc 里：**将来往这条 SET 里加列时，必须回头看一眼那 5 列**。
     */
    public int updateUserProfile(String email, String address, String selfintroduction, String username) {
        String sql = "UPDATE mall_management_sql.user SET email=?, address=?, selfintroduction=? WHERE username=?";
        return jdbcClient.sql(sql).params(email, address, selfintroduction, username).update();
    }
}
