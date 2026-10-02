package com.minke.mallmanagement.user.controller;

import com.minke.mallmanagement.user.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * user 域的 Web 层（三层重构第三笔：user 域）。**只剩路由 + 入参绑定 + 一行委托**；
 * 业务规则、上传编排、响应组装全在 {@link UserService}，SQL 全在
 * {@code com.minke.mallmanagement.user.dao.UserDao}。
 *
 * <h2>【本类收 5 个端点 —— 不含 {@code userSelect}】</h2>
 * {@code loginSelect} / {@code loginAdd} / {@code getUserInfo} / {@code updateAvatar} /
 * {@code updateUserInfo}。⚠️ {@code /message/userSelect} <b>不归本笔</b>：
 * 它的路由在 {@code MessageController}、服务在 {@code UserCacheService}，
 * 只是碰巧读 {@code user} 域的表 —— 归 message 域那一笔。
 *
 * <h2>【为什么本类现在**没有**任何注解式行为（{@code @Transactional} / 缓存）】</h2>
 * 三层前 {@code loginAdd} 上挂着全仓<b>唯一一处真 {@code @Transactional}</b>
 * （{@code UserController:57}），Boot 的 {@code TransactionAutoConfiguration} 为它建了事务代理
 * ⇒ 本 bean 当时<b>是 CGLIB 代理</b>。注解随方法体下沉到 {@link UserService#loginAdd} 后，
 * 本类恢复无代理 —— 这个**状态翻转**由
 * {@code MallManagementApplicationTests.userControllerIsNotAopProxied} 钉住
 * （该断言的 javadoc 记了翻转前后的两次实测）。
 *
 * <p>⚠️ 别图省事把 {@code @Transactional} 或缓存注解挂回本类：本类的职责边界是
 * 「把 HTTP 参数交给 Service」，事务边界 = 一次业务操作，属于 Service。
 *
 * <h2>【{@code @RequestParam} 的五个默认值逐字未改】</h2>
 * {@code image} 的 {@code http://localhost:8088/img/default_user.png}、
 * {@code customerid} / {@code merchantid} 的 {@code 暂无数据}，
 * 参数名 {@code userid}（对应 Java 变量 {@code id}）、{@code age}（{@code Integer}）等
 * 全部照搬 —— 前端 7 个调用点（{@code LoginPage.vue:131/142/216}、
 * {@code UserInfoPage.vue:96/130/149}、{@code GoodsUp.vue:150}）按这些名字传参，
 * 改一个就断一个。
 */
@RestController
@RequestMapping("/user")
@CrossOrigin
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    //这是一个接口（用于返回登录界面对应的数据，决定了此次登录去往哪个端【客户端or商家端】）
    @PostMapping("/loginSelect")
    public int loginSelect(
            @RequestParam("username") String username,       // 对应数据库 login 表的 UserName
            @RequestParam("password") String password        // 对应数据库 login 表的 PassWord
    ) {
        return userService.loginSelect(username, password);
    }

    //这是一个接口（用于最早期的注册，只更新最基础的用户信息）
    @PostMapping("/loginAdd")
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
        // ⚠️ 三层前挂在本方法上的 @Transactional 已随方法体下沉到 UserService.loginAdd ——
        // 不在这里补回来。事务边界 = 一次业务操作，属于 Service。
        return userService.loginAdd(imageAdd, usernameAdd, usertypeAdd, id,
                customeridAdd, merchantidAdd, password, ageAdd, genderAdd);
    }

    //这是一个接口（用于返回对应前端页面的用户信息）
    @PostMapping("/getUserInfo")
    public Map<String, Object> getUserInfo(
            @RequestParam("username") String username,
            @RequestParam("password") String password
    ) {
        return userService.getUserInfo(username, password);
    }

    // 这是一个接口（用于更改前端用户的头像）
    @PostMapping("/updateAvatar")
    public Map<String, Object> updateAvatar(
            @RequestParam("username") String username,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return userService.updateAvatar(username, file);
    }

    //这是一个接口（用于更新个人信息）
    @PostMapping("/updateUserInfo")
    public Map<String, Object> updateUserInfo(
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("address") String address,
            @RequestParam("selfintroduction") String selfintroduction
    ) {
        return userService.updateUserInfo(username, email, address, selfintroduction);
    }
}
