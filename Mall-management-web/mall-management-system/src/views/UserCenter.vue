<template>
  <!--
    个人中心 = **中转站**（2026-10-19 用户令）。

    ⚠️ 本组件不再画顶部 Tab / 左菜单：历史购买、好友、会员中心、商家入驻
       都是**独立全宽页面**（各自的路由记录，父级 `meta.noRail` 已隐藏侧栏）。
       这里只留一个出口给子路由，保证 `/customer/user/*`、`/merchant/user/*`
       的既有地址与角色守卫全部不变。

    ⚠️ 退出登录仍在 `UserInfoPage.vue` 的身份卡里（那是个人中心首页），
       不随菜单一起消失。
  -->
  <router-view />
</template>

<script setup>
// 无脚本：中转层不持有状态。
</script>
