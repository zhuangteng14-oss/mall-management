package com.minke.mallmanagement.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteCatalog} 的判据：给模型的页面清单必须**真实、按角色过滤、可归位**。
 *
 * <p>这份表是「模型只能从这里挑」的边界。它一旦漏了真实路由，模型就会自己编一个地址；
 * 一旦混进别的角色壳的路径，导航会被路由守卫踢回首页。
 */
class RouteCatalogTest {

    @Test
    @DisplayName("商家只看到商家壳；客户只看到客户壳")
    void routesAreFilteredByRole() {
        List<String> merchant = RouteCatalog.routesFor(2).stream().map(RouteCatalog.Route::path).toList();
        List<String> customer = RouteCatalog.routesFor(1).stream().map(RouteCatalog.Route::path).toList();

        assertThat(merchant).contains("/merchant/user/add", "/merchant/stats", "/merchant/after-sales");
        assertThat(merchant).noneMatch(p -> p.startsWith("/customer"));
        assertThat(customer).contains("/customer/user/member", "/customer/orders");
        assertThat(customer).noneMatch(p -> p.startsWith("/merchant"));
    }

    @Test
    @DisplayName("身份未知时两壳全列（最终落点由前端角色守卫兜底）")
    void unknownRoleSeesBothShells() {
        List<String> all = RouteCatalog.routesFor(null).stream().map(RouteCatalog.Route::path).toList();

        assertThat(all).contains("/merchant/stats", "/customer/user/member");
    }

    @Test
    @DisplayName("Prompt 清单逐字带真实路径与中文说明，且说明里如实写了「没有钱包页 / 没有评价页」")
    void promptCatalogCarriesRealPathsAndTruth() {
        String merchantPrompt = RouteCatalog.promptCatalog(2);
        String customerPrompt = RouteCatalog.promptCatalog(1);

        assertThat(merchantPrompt).contains("- /merchant/user/add", "- /merchant/stats", "- /merchant/after-sales")
                .contains("没有独立的钱包页")
                .contains("独立的评价管理页");
        assertThat(customerPrompt).contains("- /customer/user/member")
                .doesNotContain("/merchant/stats");
    }

    @Test
    @DisplayName("别名与跨壳归位：旧地址 / 外行说法都落到真实路由")
    void resolveNormalizesAliasesAndShells() {
        assertThat(RouteCatalog.resolve("/merchant/aftersale", 2)).isEqualTo("/merchant/after-sales");
        assertThat(RouteCatalog.resolve("/customer/wallet", 1)).isEqualTo("/customer/user/member");
        assertThat(RouteCatalog.resolve("/customer/user/wallet", 1)).isEqualTo("/customer/user/member");
        // 模型串壳：客户壳下给了商家路径 ⇒ 改写成客户壳同构页
        assertThat(RouteCatalog.resolve("/merchant/user/member", 1)).isEqualTo("/customer/user/member");
        assertThat(RouteCatalog.resolve("/merchant/orders", 1)).isEqualTo("/customer/orders");
        // 客户没有发布页 ⇒ 「怎么上架商品」最近的真实落点是先入驻
        assertThat(RouteCatalog.resolve("/merchant/user/add", 1)).isEqualTo("/customer/user/apply");
        // 归一化：query / hash / 末尾斜杠 / 大小写
        assertThat(RouteCatalog.resolve("/merchant/stats/?tab=week#top", 2)).isEqualTo("/merchant/stats");
        assertThat(RouteCatalog.resolve("/Merchant/Stats", 2)).isEqualTo("/merchant/stats");
    }

    @Test
    @DisplayName("编造的 / 当前角色没有的页面一律 null（编排层降级，不把用户送进守卫）")
    void resolveRejectsUnknownOrForeignPaths() {
        assertThat(RouteCatalog.resolve("/merchant/aftersale/xxx", 2)).isNull();
        assertThat(RouteCatalog.resolve("/customer/wallet/balance", 1)).isNull();
        assertThat(RouteCatalog.resolve("/merchant/stats", 1)).isNull();
        assertThat(RouteCatalog.resolve("/customer/stats", 1)).isNull();
        assertThat(RouteCatalog.resolve("", 2)).isNull();
        assertThat(RouteCatalog.resolve(null, 2)).isNull();
        assertThat(RouteCatalog.isAllowed("/product/1", 2)).isFalse();
    }

    @Test
    @DisplayName("降级匹配：用字典里的「用户说」说法找页面；并列 / 太短就说不准")
    void guessUsesDictionaryPhrases() {
        assertThat(RouteCatalog.guess("我要看评论", 2)).isEqualTo("/merchant/user/list");
        assertThat(RouteCatalog.guess("我要管理我的钱", 2)).isEqualTo("/merchant/user/member");
        assertThat(RouteCatalog.guess("我要去看店铺数据", 2)).isEqualTo("/merchant/stats");
        assertThat(RouteCatalog.guess("怎么上架商品", 2)).isEqualTo("/merchant/user/add");
        // 两字名词也是页面线索（模型超时时靠它兜底）
        assertThat(RouteCatalog.guess("我想改一下那个 299 元耳机的价格", 2)).isEqualTo("/merchant/user/list");
        assertThat(RouteCatalog.guess("怎么处理退款", 2)).isEqualTo("/merchant/after-sales");
        // 同一句话在客户壳落到订单页（客户没有售后工作台）
        assertThat(RouteCatalog.guess("怎么处理退款", 1)).isEqualTo("/customer/orders");
        assertThat(RouteCatalog.guess("我要看评论", 1)).isEqualTo("/customer/orders");

        assertThat(RouteCatalog.guess("今天天气不错", 2)).isNull();
        assertThat(RouteCatalog.guess("我要看", 2)).isNull();
        assertThat(RouteCatalog.guess("", 2)).isNull();
    }

    @Test
    @DisplayName("回给用户的说明不含「用户说…时选它」这段给模型的提示")
    void userSummaryStripsPromptHint() {
        String member = RouteCatalog.userSummary(RouteCatalog.find("/merchant/user/member"));
        assertThat(member).contains("余额", "没有独立的钱包页").doesNotContain("用户说");

        String list = RouteCatalog.userSummary(RouteCatalog.find("/merchant/user/list"));
        assertThat(list).contains("评价管理页").doesNotContain("用户说");
    }

    @Test
    @DisplayName("find 只做精确查找，不替模型猜")
    void findIsExact() {
        assertThat(RouteCatalog.find("/merchant/user/list").title()).isEqualTo("查看商品");
        assertThat(RouteCatalog.find("/merchant/aftersale")).isNull();
        assertThat(RouteCatalog.find("/merchant")).isNotNull();
    }
}
