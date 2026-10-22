package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义路由的判据 —— 全部用内存向量，**不出网**。
 *
 * <p>真模型相似度在探针里验（{@code probe-chat-semantic-router.mjs}）；本文件只锁
 * 「注册表 → 向量 → 余弦 → 阈值 / 角色过滤」这条确定性逻辑。
 */
class SemanticRouterTest {

    @Test
    @DisplayName("注册表：三个点名意图各有 3~5 条标准问法，路径与 RouteCatalog 一致")
    void registryHasCoreIntentsAndUtterances() {
        assertThat(byIntent(ChatIntent.GOODS_MANAGE)).isEqualTo(5);
        assertThat(byIntent(ChatIntent.MERCHANT_DASHBOARD)).isEqualTo(5);
        assertThat(byIntent(ChatIntent.ORDER_QUERY)).isEqualTo(5);
        assertThat(byIntent(ChatIntent.ASSET_QUERY)).isBetween(3, 5);

        SemanticRouteRegistry.SemanticRoute goods = first(ChatIntent.GOODS_MANAGE);
        assertThat(goods.targetPath()).isEqualTo("/merchant/user/list");
        assertThat(RouteCatalog.resolve(goods.targetPath(), 2)).isEqualTo("/merchant/user/list");
        assertThat(first(ChatIntent.MERCHANT_DASHBOARD).targetPath()).isEqualTo("/merchant");
    }

    @Test
    @DisplayName("余弦：精确命中 GOODS_MANAGE / MERCHANT_DASHBOARD，且 confident=true")
    void routesPointedExamples() {
        Fixture f = fixture();
        SemanticRouter router = new SemanticRouter(f.embedder, true, 0.75, false);

        SemanticRouter.Result goods = router.route("怎么看我上架的商品", 2);
        assertThat(goods).isNotNull();
        assertThat(goods.intent()).isEqualTo(ChatIntent.GOODS_MANAGE);
        assertThat(goods.targetPath()).isEqualTo("/merchant/user/list");
        assertThat(goods.score()).isEqualTo(1.0);
        assertThat(goods.confident()).isTrue();

        SemanticRouter.Result dashboard = router.route("怎么前往商家工作台", 2);
        assertThat(dashboard.intent()).isEqualTo(ChatIntent.MERCHANT_DASHBOARD);
        assertThat(dashboard.confident()).isTrue();

        SemanticRouter.Result asset = router.route("我花出去的钱去哪了", 2);
        assertThat(asset.intent()).isEqualTo(ChatIntent.ASSET_QUERY);
        assertThat(asset.confident()).isTrue();
    }

    @Test
    @DisplayName("护栏：最高相似度 < 0.75 ⇒ confident=false（编排层据此澄清）")
    void lowScoreIsNotConfident() {
        Fixture f = fixture();
        SemanticRouter router = new SemanticRouter(f.embedder, true, 0.75, false);

        SemanticRouter.Result result = router.route("今天天气不错", 2);

        assertThat(result).isNotNull();
        assertThat(result.score()).isLessThan(0.75);
        assertThat(result.confident()).isFalse();
    }

    @Test
    @DisplayName("角色过滤：客户问「商家工作台」不会命中 MERCHANT_DASHBOARD")
    void merchantOnlyRouteIsFilteredForCustomer() {
        Fixture f = fixture();
        SemanticRouter router = new SemanticRouter(f.embedder, true, 0.75, false);

        SemanticRouter.Result result = router.route("怎么前往商家工作台", 1);

        assertThat(result == null || result.intent() != ChatIntent.MERCHANT_DASHBOARD).isTrue();
        assertThat(result == null || !result.confident()).isTrue();
    }

    @Test
    @DisplayName("懒加载：preload=false 时首次 route 才向量化，之后 isReady=true")
    void lazyLoadsOnFirstRoute() {
        Fixture f = fixture();
        SemanticRouter router = new SemanticRouter(f.embedder, true, 0.75, false);

        assertThat(router.isReady()).isFalse();
        assertThat(router.size()).isZero();

        router.route("查我的订单", 1);

        assertThat(router.isReady()).isTrue();
        assertThat(router.size()).isEqualTo(f.totalUtterances);
        // 1 次预加载全部标准问法 + 1 次查询向量。
        assertThat(f.embedder.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("未启用 / 向量化失败 ⇒ route 返回 null（编排层降级，不抛给调用方）")
    void disabledOrBrokenEmbedderDegrades() {
        Fixture f = fixture();
        f.embedder.enabled = false;
        assertThat(new SemanticRouter(f.embedder, true, 0.75, false).route("查我的订单", 1)).isNull();

        Fixture broken = fixture();
        broken.embedder.fail = true;
        assertThat(new SemanticRouter(broken.embedder, true, 0.75, false).route("查我的订单", 1)).isNull();
    }

    // ==================== 替身 ====================

    private record Fixture(FakeEmbedder embedder, int totalUtterances) {
    }

    /**
     * 给每条标准问法分配一个唯一 one-hot 维；查询句按用例映射到对应问法的向量。
     * 未登记查询 = 全 1 均匀向量（与任何 one-hot 的余弦都远低于 0.75）。
     */
    private static Fixture fixture() {
        List<SemanticRouteRegistry.SemanticRoute> routes = SemanticRouteRegistry.all();
        List<String> utterances = new ArrayList<>();
        for (SemanticRouteRegistry.SemanticRoute route : routes) {
            utterances.addAll(route.utterances());
        }
        int dim = utterances.size();
        FakeEmbedder embedder = new FakeEmbedder(dim);
        for (int i = 0; i < dim; i++) {
            float[] oneHot = new float[dim];
            oneHot[i] = 1f;
            embedder.put(utterances.get(i), oneHot);
        }
        embedder.put("怎么看我上架的商品", embedder.vectorOf("我要管理商品"));
        embedder.put("怎么前往商家工作台", embedder.vectorOf("怎么去商家工作台"));
        embedder.put("我花出去的钱去哪了", embedder.vectorOf("我花出去的钱去哪了"));
        return new Fixture(embedder, dim);
    }

    private static final class FakeEmbedder implements TextEmbedder {
        private final int dim;
        private final Map<String, float[]> vectors = new LinkedHashMap<>();
        private boolean enabled = true;
        private boolean fail;
        private int calls;

        private FakeEmbedder(int dim) {
            this.dim = dim;
        }

        private void put(String text, float[] vector) {
            vectors.put(text, vector);
        }

        private float[] vectorOf(String text) {
            return vectors.get(text);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls++;
            if (fail) {
                throw new EmbeddingException("test failure");
            }
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] known = vectors.get(text);
                if (known != null) {
                    out.add(known.clone());
                    continue;
                }
                float[] uniform = new float[dim];
                for (int i = 0; i < dim; i++) {
                    uniform[i] = 1f;
                }
                out.add(uniform);
            }
            return out;
        }
    }

    private static int byIntent(ChatIntent intent) {
        return SemanticRouteRegistry.all().stream()
                .filter(route -> route.intent() == intent)
                .mapToInt(route -> route.utterances().size())
                .sum();
    }

    private static SemanticRouteRegistry.SemanticRoute first(ChatIntent intent) {
        return SemanticRouteRegistry.all().stream()
                .filter(route -> route.intent() == intent)
                .findFirst()
                .orElseThrow();
    }
}
