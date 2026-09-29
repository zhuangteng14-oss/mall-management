package com.minke.mallmanagement_controller;

import com.minke.mallmanagement_service.DistributedLockService;
import com.minke.mallmanagement_service.DistributedLockService.LockName;
import com.minke.mallmanagement_service.FriendCacheService;
import com.minke.mallmanagement_service.UserCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;

import java.util.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/message")
@CrossOrigin
public class MallManagementMessage {

    @Autowired
    private JdbcTemplate MessagejdbcTemplate;

    @Autowired
    private UserCacheService userCacheService;

    // 好友两族（friendList / friendRequests）的缓存承载层。缓存注解全在它身上 ⇒
    // **本 Controller 不持有任何缓存注解、不被代理**（有防回归测试断言，同 goods 那套）。
    @Autowired
    private FriendCacheService friendCacheService;

    // B2 的锁。与缓存注解同理：注解全在别的类身上 ⇒ 本 Controller 不被代理（有防回归测试断言）。
    @Autowired
    private DistributedLockService distributedLockService;

    // ===================== 消息相关接口（基于会话表） =====================
    @PostMapping("/conversations")
    public Map<String, Object> getConversations(@RequestParam("currentId") String currentId,
                                                @RequestParam("currentType") String currentType) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT other_id, other_type, MAX(create_time) as last_time " +
                "FROM ( " +
                "  SELECT " +
                "    CASE WHEN from_id = ? THEN to_id ELSE from_id END AS other_id, " +
                "    CASE WHEN from_id = ? THEN to_type ELSE from_type END AS other_type, " +
                "    create_time " +
                "  FROM message WHERE from_id = ? OR to_id = ?" +
                ") t GROUP BY other_id, other_type ORDER BY last_time DESC";
        List<Map<String, Object>> list = MessagejdbcTemplate.queryForList(sql, currentId, currentId, currentId, currentId);

        for (Map<String, Object> conv : list) {
            String otherId = (String) conv.get("other_id");
            String otherType = (String) conv.get("other_type");
            Object lastTimeObj = conv.get("last_time");
            LocalDateTime lastMsgTime = null;
            if (lastTimeObj != null) {
                if (lastTimeObj instanceof Timestamp) {
                    lastMsgTime = ((Timestamp) lastTimeObj).toLocalDateTime();
                } else if (lastTimeObj instanceof LocalDateTime) {
                    lastMsgTime = (LocalDateTime) lastTimeObj;
                }
            }

            String readSql = "SELECT last_read_time FROM user_conversation_read " +
                    "WHERE user_id = ? AND user_type = ? AND other_id = ? AND other_type = ?";
            List<Map<String, Object>> readList = MessagejdbcTemplate.queryForList(readSql, currentId, currentType, otherId, otherType);

            boolean unread = false;
            if (readList.isEmpty()) {
                unread = lastMsgTime != null;
            } else {
                Object lastReadObj = readList.get(0).get("last_read_time");
                LocalDateTime lastRead = null;
                if (lastReadObj instanceof Timestamp) {
                    lastRead = ((Timestamp) lastReadObj).toLocalDateTime();
                } else if (lastReadObj instanceof LocalDateTime) {
                    lastRead = (LocalDateTime) lastReadObj;
                }
                unread = lastMsgTime != null && (lastRead == null || lastMsgTime.isAfter(lastRead));
            }
            conv.put("unread", unread);
        }

        result.put("success", true);
        result.put("list", list);
        return result;
    }

    @PostMapping("/history")
    public Map<String, Object> getMessageHistory(@RequestParam("currentId") String currentId,
                                                 @RequestParam("currentType") String currentType,
                                                 @RequestParam("otherId") String otherId,
                                                 @RequestParam("otherType") String otherType) {
        // 查询历史消息
        String sql = "SELECT id, from_id, from_type, to_id, to_type, content, create_time " +
                "FROM message WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?) " +
                "ORDER BY create_time ASC";
        List<Map<String, Object>> list = MessagejdbcTemplate.queryForList(sql, currentId, otherId, otherId, currentId);

        // 更新最后阅读时间（使用 MySQL 的 NOW()，避免类型转换）
        String upsertSql = "INSERT INTO user_conversation_read (user_id, user_type, other_id, other_type, last_read_time) " +
                "VALUES (?, ?, ?, ?, NOW()) " +
                "ON DUPLICATE KEY UPDATE last_read_time = NOW()";
        MessagejdbcTemplate.update(upsertSql, currentId, currentType, otherId, otherType);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", list);
        return result;
    }

    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestParam("fromId") String fromId,
                                           @RequestParam("fromType") String fromType,
                                           @RequestParam("toId") String toId,
                                           @RequestParam("toType") String toType,
                                           @RequestParam("content") String content) {
        Map<String, Object> result = new HashMap<>();
        String sql = "INSERT INTO message (from_id, from_type, to_id, to_type, content, create_time) " +
                "VALUES (?, ?, ?, ?, ?, NOW())";
        try {
            int rows = MessagejdbcTemplate.update(sql, fromId, fromType, toId, toType, content);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "发送成功");
            } else {
                result.put("success", false);
                result.put("msg", "发送失败");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/getOrCreateConversation")
    public Map<String, Object> getOrCreateConversation(
            @RequestParam("currentId") String currentId,
            @RequestParam("currentType") String currentType,
            @RequestParam("otherId") String otherId,
            @RequestParam("otherType") String otherType) {

        Map<String, Object> result = new HashMap<>();

        // 获取对方用户信息（用于前端显示用户名）
        Map<String, Object> otherUser = getUserInfoById(otherId);
        if (otherUser == null) {
            result.put("success", false);
            result.put("msg", "对方用户不存在");
            return result;
        }

        // 查询双方的历史消息（按时间升序）
        String sql = "SELECT id, from_id, from_type, to_id, to_type, content, create_time " +
                "FROM message WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?) " +
                "ORDER BY create_time ASC";
        List<Map<String, Object>> messages;
        try {
            messages = MessagejdbcTemplate.queryForList(sql, currentId, otherId, otherId, currentId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
            return result;
        }

        result.put("success", true);
        result.put("otherUser", otherUser);   // 包含 id, username, usertype, gender, image
        result.put("messages", messages);
        return result;
    }
    // ===================== 好友相关接口 =====================
    //搜索好友时显示数据
    // 读路径（SELECT + 取首行）搬到 UserCacheService.publicInfo()，本方法只保留路由 + 响应组装。
    // 【本方法在 userSelect 上缓存这件事里的角色】只组装响应，**不挂任何缓存注解** ——
    //   · Service 返回 null（业务 0 行）→ 本方法构造 marker ⇒ 该 null 已被缓存（负缓存，防 ID 枚举穿透）
    //   · Service 抛异常（技术失败）→ 本方法同样构造 marker ⇒ 但**异常不进缓存**（@Cacheable 遇异常不写）
    // 两条路径的响应体与引入 UserCacheService 之前**逐字段相同**（零契约变更）。
    // ⚠️ 代价：技术异常与「查无此人」在响应**文案**上仍不可区分（既有瑕疵，见 §11，本笔不动）。
    // ⚠️ 缓存体是 Service 返回的裸行 Map，形状耦合**具体列名**（§10 边界第 4 条形态②）。
    @PostMapping("/userSelect")
    public Map<String, Object> userSelect(@RequestParam("userId") String userId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> user;
        try {
            user = userCacheService.publicInfo(userId);
        } catch (Exception e) {
            e.printStackTrace();
            user = null;
        }
        if (user != null) {
            String usertype = (String) user.get("usertype");
            String id;
            if ("客户".equals(usertype)) {           // 改为中文
                id = (String) user.get("customerid");
            } else if ("商家".equals(usertype)) {     // 改为中文
                id = (String) user.get("merchantid");
            } else {
                id = (String) user.get("customerid");
                if (id == null) id = (String) user.get("merchantid");
            }
            Map<String, Object> resultUser = new HashMap<>();
            resultUser.put("id", id);
            resultUser.put("username", user.get("username"));
            resultUser.put("usertype", usertype);
            resultUser.put("gender", user.get("gender"));
            resultUser.put("image", null);   // 无 image 字段，设为 null
            result.put("success", true);
            result.put("user", resultUser);
            return result;
        }
        result.put("success", false);
        result.put("msg", "用户不存在");
        return result;
    }

    //为好友表添加待处理的数据
    // ⚠️ B2：方法体在 Redisson 锁下执行，锁 key = pairKey(customerId, friendId)（**无序对**，
    //    理由见 DistributedLockService.pairKey 的 javadoc —— 本接口的查重 SQL 是 OR 双向的，
    //    按入参顺序拼会让 A→B 与 B→A 各持一把锁），指标 tag = friend-add。
    //    锁在这里只把「查 + 插」合成原子（既有查重已正确，锁补的是 TOCTOU）；
    //    拿不到锁 ⇒ fail-open 直行原路径 —— 与下面「唯一出口」注释的约定 ① 一致（该分支不改库，
    //    故天然不必动 evictPair）。
    @PostMapping("/friendAdd")
    public Map<String, Object> friendAdd(@RequestParam("customerId") String customerId,
                                         @RequestParam("friendId") String friendId) {
        return distributedLockService.runLocked(LockName.FRIEND_ADD,
                DistributedLockService.pairKey(customerId, friendId),
                () -> friendAddLocked(customerId, friendId));
    }

    /** friendAdd 的临界区：**原方法体逐字未动**（B2 的边界），含唯一出口处的 evictPair。 */
    private Map<String, Object> friendAddLocked(String customerId, String friendId) {
        Map<String, Object> result = new HashMap<>();
        if (customerId.equals(friendId)) {
            result.put("success", false);
            result.put("msg", "不能添加自己为好友");
            return result;
        }
        String checkSql = "SELECT id, if_friend FROM friend WHERE (customerid = ? AND friendid = ?) OR (customerid = ? AND friendid = ?)";
        List<Map<String, Object>> existing = MessagejdbcTemplate.queryForList(checkSql, customerId, friendId, friendId, customerId);
        if (!existing.isEmpty()) {
            int ifFriend = (Integer) existing.get(0).get("if_friend");
            if (ifFriend == 1) {
                result.put("success", false);
                result.put("msg", "已经是好友");
            } else {
                result.put("success", false);
                result.put("msg", "已发送过好友申请，请等待对方处理");
            }
            return result;
        }
        String sql = "INSERT INTO friend (customerid, friendid, if_friend) VALUES (?, ?, 0)";
        try {
            int rows = MessagejdbcTemplate.update(sql, customerId, friendId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "好友申请已发送");
            } else {
                result.put("success", false);
                result.put("msg", "添加失败");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式（最完整的注释在 friendUp 的唯一出口上方），
        //    若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调 evictPair()。
        // 本方法只有 1 条写语句、在 try 内、catch 吞异常（该语句抛错时未改库）⇒ 唯一出口覆盖
        // 100% 的可能改库路径。⚠️ 上面**两处**提前 return（`customerId.equals(friendId)` 与
        // 「已存在」分支）都只跑 SELECT、未改库 ⇒ 不必清。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    //查询好友表内对应申请人的待处理信息
    // 读路径搬到 FriendCacheService.listRequests()，本方法只保留路由 + 响应组装（同 friendSelect 那套）。
    // 不挂缓存注解；0 行同样是 `{success:true, list:[]}`（**成功空值**，不上负缓存）。
    @PostMapping("/selectFriend")
    public Map<String, Object> selectFriend(@RequestParam("friendId") String friendId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> applicants = friendCacheService.listRequests(friendId);
            result.put("success", true);
            result.put("list", applicants);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    //更新好友表内的数据，让两个用户互为好友
    @PostMapping("/friendUp")
    public Map<String, Object> friendUp(@RequestParam("customerId") String customerId,
                                        @RequestParam("friendId") String friendId) {
        Map<String, Object> result = new HashMap<>();
        String updateSql = "UPDATE friend SET if_friend = 1 WHERE customerid = ? AND friendid = ? AND if_friend = 0";
        String insertSql = "INSERT INTO friend (customerid, friendid, if_friend) VALUES (?, ?, 1)";
        try {
            int updated = MessagejdbcTemplate.update(updateSql, customerId, friendId);
            if (updated > 0) {
                MessagejdbcTemplate.update(insertSql, friendId, customerId);
                result.put("success", true);
                result.put("msg", "已添加为好友");
            } else {
                result.put("success", false);
                result.put("msg", "申请不存在或已处理");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— **无条件**放在这里，**不能**塞进上面的 if (updated > 0) 分支里。
        //    理由：本方法**没有 @Transactional**，却有**两条**写语句。若 UPDATE 提交后 INSERT 抛错
        //    ⇒ 走 catch ⇒ 返回 {success:false}，**但库已经被改了**（(customerId, friendId) 已成
        //    if_friend = 1）⇒ 把清缓存挂在「成功分支」上会让这条路径漏清。
        //    ⚠️ 这与 UserCacheService.evictPublic() 的论证**方向相反**：那里靠「loginAdd 是
        //    @Transactional、异常路径回滚 ⇒ 不改库」证明唯一出口覆盖 100%；这里是「无事务 ⇒
        //    异常路径**可能已经改库**」⇒ 出口必须无条件覆盖。
        //    三个写接口共用同一模式，若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调它。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    //删除掉不通过好友申请时。对应的待处理的好友申请数据
    @PostMapping("/friendDe")
    public Map<String, Object> friendDe(@RequestParam("customerId") String customerId,
                                        @RequestParam("friendId") String friendId) {
        Map<String, Object> result = new HashMap<>();
        String sql = "DELETE FROM friend WHERE customerid = ? AND friendid = ? AND if_friend = 0";
        try {
            int rows = MessagejdbcTemplate.update(sql, customerId, friendId);
            if (rows > 0) {
                result.put("success", true);
                result.put("msg", "已拒绝申请");
            } else {
                result.put("success", false);
                result.put("msg", "申请不存在");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        // ⚠️ 本方法的唯一出口 —— 三个写接口共用同一模式（最完整的注释在 friendUp 的唯一出口上方），
        //    若新增 return 请确认：① 该分支不改库，或 ② 在该分支前也调 evictPair()。
        // 本方法只有 1 条写语句、在 try 内、catch 吞异常（该语句抛错时未改库）⇒ 唯一出口覆盖 100%。
        friendCacheService.evictPair(customerId, friendId);
        return result;
    }

    //打印好友界面的好友信息
    // 读路径（外层 SELECT + 逐行 enrich）搬到 FriendCacheService.listFriends()，本方法只保留路由 + 响应组装。
    // 【本方法在 friendSelect 上缓存这件事里的角色】只组装响应，**不挂任何缓存注解** ——
    //   · 正常路径：Service 返回列表（**含空列表 `[]`**）⇒ 该列表已被缓存（cacheName = friendList）
    //   · 技术异常：Service **上抛** ⇒ 缓存不写入 ⇒ 由下面**既有**的 catch 构造 marker
    // ⚠️ 与 userSelect 的差异：这里 0 行返回的是 `{success:true, list:[]}`（**成功空值**），
    //   不是失败 marker ⇒ **不需要**（也不应该）翻译成 null 去套负缓存。空列表照样被缓存 ⇒
    //   同 key 再读不再打 DB，防穿透在「重复打同一键」这一层已经成立。
    // ⚠️ 缓存体是裸 List<Map>，形状耦合**具体列名**（§10 边界第 4 条形态②）。
    @PostMapping("/friendSelect")
    public Map<String, Object> friendSelect(@RequestParam("customerId") String customerId) {
        Map<String, Object> result = new HashMap<>();
        // 增加 AND friendid != ? 防止返回自己（该条件在 Service 的 SQL 里，逐字未动）
        try {
            List<Map<String, Object>> friendList = friendCacheService.listFriends(customerId);
            result.put("success", true);
            result.put("list", friendList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    // 辅助方法：根据id获取用户信息（同样从 user 表查询）
    private Map<String, Object> getUserInfoById(String userId) {
        String sql = "SELECT customerid, merchantid, username, usertype, gender FROM user WHERE customerid = ? OR merchantid = ?";
        try {
            List<Map<String, Object>> list = MessagejdbcTemplate.queryForList(sql, userId, userId);
            if (!list.isEmpty()) {
                Map<String, Object> user = list.get(0);
                String usertype = (String) user.get("usertype");
                String id;
                if ("客户".equals(usertype)) {
                    id = (String) user.get("customerid");
                } else if ("商家".equals(usertype)) {
                    id = (String) user.get("merchantid");
                } else {
                    id = (String) user.get("customerid");
                    if (id == null) id = (String) user.get("merchantid");
                }
                // 获取头像
                String image = null;
                if ("客户".equals(usertype) && id != null) {
                    String imgSql = "SELECT image FROM customerimage WHERE customerid = ?";
                    List<Map<String, Object>> imgList = MessagejdbcTemplate.queryForList(imgSql, id);
                    if (!imgList.isEmpty()) image = (String) imgList.get(0).get("image");
                } else if ("商家".equals(usertype) && id != null) {
                    String imgSql = "SELECT image FROM merchantimage WHERE merchantid = ?";
                    List<Map<String, Object>> imgList = MessagejdbcTemplate.queryForList(imgSql, id);
                    if (!imgList.isEmpty()) image = (String) imgList.get(0).get("image");
                }
                Map<String, Object> result = new HashMap<>();
                result.put("id", id);
                result.put("username", user.get("username"));
                result.put("usertype", usertype);
                result.put("gender", user.get("gender"));
                result.put("image", image); // 可能为null，前端会使用默认头像
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}