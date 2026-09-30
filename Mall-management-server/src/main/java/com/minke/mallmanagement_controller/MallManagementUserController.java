package com.minke.mallmanagement_controller;

import com.minke.mallmanagement_service.UploadStorage;
import com.minke.mallmanagement_service.UserCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/mysqlLogin")
@CrossOrigin
public class MallManagementUserController {

    //登录部分
    @Autowired
    private JdbcTemplate LoginjdbcTemplate;//调取Spring里封装好的jdbc

    @Autowired
    private UserCacheService userCacheService;

    @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}")
    private String uploadDir;

    //这是一个接口（用于返回登录界面对应的数据，决定了此次登录去往哪个端【客户端or商家端】）
    @PostMapping("/loginSelect")
    public int loginSelect(
            @RequestParam("username") String username,       // 对应数据库 login 表的 UserName
            @RequestParam("password") String password        // 对应数据库 login 表的 PassWord
    ) {
        System.out.println(username + "," + password);

        // 查询数据库是否有用户输入的账号密码并返回对应的 Role 值（参数化查询）
        String sql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ? AND PassWord = ?";

        try {
            Integer role = LoginjdbcTemplate.queryForObject(sql, Integer.class, username, password);
            return role != null ? role : 0;
        } catch (Exception e) {
            // 用户不存在或查询异常 → 返回 0
            return 0;
        }
    }



    //这是一个接口（用于最早期的注册，只更新最基础的用户信息）
    @PostMapping("/loginAdd")
    @Transactional
    public String loginAdd(
            @RequestParam(value = "image", defaultValue = "http://localhost:8088/img/default_user.png") String imageAdd,
            @RequestParam("username") String usernameAdd,
            @RequestParam("usertype") String usertypeAdd,
            @RequestParam("userid") String id,
            @RequestParam(value = "customerid", defaultValue = "暂无数据") String customeridAdd,
            @RequestParam(value = "merchantid", defaultValue = "暂无数据") String merchantidAdd,
            @RequestParam("password") String password,
            @RequestParam("age") Integer ageAdd,
            @RequestParam("gender") String genderAdd
    ) {
        // 检查用户是否已存在
        String checkSql = "SELECT UserName FROM mall_management_sql.login WHERE UserName = ?";
        boolean userExists = !LoginjdbcTemplate.queryForList(checkSql, usernameAdd).isEmpty();
        if (userExists) {
            return "0";  // 用户已存在，返回0
        }
        int role;
        // 根据用户类型处理
        if ("客户".equals(usertypeAdd)) {
            // 插入 user 基础表
            customeridAdd = id;
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.user (username, usertype, customerid, gender, age) VALUES (?, ?, ?, ?, ?)",
                    usernameAdd, usertypeAdd, customeridAdd, genderAdd, ageAdd
            );
            // 插入客户头像表
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.customerimage (UserName, CustomerId, image) VALUES (?, ?, ?)",
                    usernameAdd, customeridAdd, imageAdd
            );
            role = 1;
            // 插入登录表
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.login (UserName, Password, Role, Id) VALUES (?, ?, ?, ?)",
                    usernameAdd, password, role, customeridAdd
            );
        } else if ("商家".equals(usertypeAdd)) {
            merchantidAdd = id;
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.user (username, usertype, merchantid, gender, age) VALUES (?, ?, ?, ?, ?)",
                    usernameAdd, usertypeAdd, merchantidAdd, genderAdd, ageAdd
            );
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.merchantimage (UserName, MerchantId, image) VALUES (?, ?, ?)",
                    usernameAdd, merchantidAdd, imageAdd
            );
            role = 2;
            LoginjdbcTemplate.update(
                    "INSERT INTO mall_management_sql.login (UserName, PassWord, Role, Id) VALUES (?, ?, ?, ?)",
                    usernameAdd, password, role, merchantidAdd
            );
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

    //这是一个接口（用于返回对应前端页面的用户信息）
    @PostMapping("/getUserInfo")
    public Map<String, Object> getUserInfo(
            @RequestParam("username") String username,
            @RequestParam("password") String password
    ) {

        // 1. 验证账号密码
        String checkSql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ? AND PassWord = ?";
        try {
            Integer role = LoginjdbcTemplate.queryForObject(checkSql, Integer.class, username, password);
            if (role == null) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        // 2. 查询 user 表全部字段
        String userSql = "SELECT * FROM mall_management_sql.user WHERE username = ?";
        Map<String, Object> userInfo;
        try {
            userInfo = LoginjdbcTemplate.queryForMap(userSql, username);
        } catch (Exception e) {
            return null;
        }

        // 3. 根据 usertype（中文：商家或客户）查询对应的图片地址
        Object userTypeObj = userInfo.get("usertype"); // 假设字段名就是 usertype
        if (userTypeObj != null) {
            String userType = userTypeObj.toString();
            String imageSql = null;
            if ("客户".equals(userType)) {
                imageSql = "SELECT image FROM mall_management_sql.customerimage WHERE username = ?";
            } else if ("商家".equals(userType)) {
                imageSql = "SELECT image FROM mall_management_sql.merchantimage WHERE username = ?";
            }

            if (imageSql != null) {
                try {
                    String image = LoginjdbcTemplate.queryForObject(imageSql, String.class, username);
                    userInfo.put("image", image);
                } catch (Exception e) {
                    // 无图片或查询失败，不添加 image 字段（或者可以添加 null）
                    // userInfo.put("image", null);
                }
            }
        }

        return userInfo;
    }

    // 这是一个接口（用于更改前端用户的头像）
    @PostMapping("/updateAvatar")
    public Map<String, Object> updateAvatar(
            @RequestParam("username") String username,
            @RequestParam("file") MultipartFile file
    ) throws IOException {

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
        String roleSql = "SELECT Role FROM mall_management_sql.login WHERE UserName = ?";
        Integer role = 0;

        try {
            role = LoginjdbcTemplate.queryForObject(roleSql, Integer.class, username);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            e.printStackTrace();
            return result;
        }

        // 更新头像
        String updateSql;
        if (role == 1) {
            updateSql = "UPDATE mall_management_sql.customerimage SET image = ? WHERE username = ?";
        } else if (role == 2) {
            updateSql = "UPDATE mall_management_sql.merchantimage SET image = ? WHERE username = ?";
        } else {
            result.put("success", false);
            result.put("msg", "角色错误");
            return result;
        }

        LoginjdbcTemplate.update(updateSql, imageUrl, username);

        result.put("success", true);
        result.put("imageUrl", imageUrl);
        return result;
    }


    //这是一个接口（用于更新个人信息）
    @PostMapping("/updateUserInfo")
    public Map<String, Object> updateUserInfo(
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("address") String address,
            @RequestParam("selfintroduction") String selfintroduction
    ) {
        Map<String, Object> result = new HashMap<>();

        String updateSql = "UPDATE mall_management_sql.user SET email=?, address=?, selfintroduction=? WHERE username=?";

        try {
            LoginjdbcTemplate.update(updateSql, email, address, selfintroduction, username);
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
