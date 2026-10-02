package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /mysqlGoods/productCodePrice} 的**响应契约**实测 —— P0 #2 的另一半，2026-09-30。
 *
 * <p>Service 侧的缓存行为在 {@code com.minke.mallmanagement.goods.service.GoodsPriceCacheTest}；本类只管一件事：
 * <b>Service 换成了「裸行 / null / 抛异常」三态之后，这条接口对调用方还是不是原来的样子</b>。
 *
 * <h2>为什么非要有这个类</h2>
 * 本次把 try/catch 从 Service 搬回了 Controller。搬迁是纯重构的<b>看似</b>—— 但它同时把
 * Service 的「异常出口」打开了：从前 Service 从不抛，现在会抛。任何一处漏接，
 * 表现都是 <b>HTTP 500</b>：前端三个调用点（{@code GoodsUp.vue:275}、{@code ProductPage.vue:193}
 * 与 {@code :245}）全是 {@code await res.json()} 之后读 {@code data.success}，
 * 500 会让它们走进各自那个<b>空 catch / console.error</b>，用户看到的是「点了没反应」。
 * 故「仍是 200 + {success:false}」这件事必须被钉死在测试里，而不是靠读代码相信。
 *
 * <h2>反向断言是哪一条</h2>
 * {@link #manyRowsFailureIsStillHttp200AndNotA500()} —— 它喂一个真异常进去，断言的是
 * <b>没有</b>变成 500。这是本类里唯一一条「改前假不成、改后真」的用例：
 * 改前异常在 Service 里被吞掉，根本到不了 Controller 的 catch，这条用例测的
 * catch 分支是**死的**；改后它才是活路径。（改前的等价分支由 Service 内部那条 catch 覆盖，
 * 已随本次改动删除，其性质由 {@code GoodsPriceCacheTest} 接手。）
 *
 * <h2>不在 A3_ENABLED 门后</h2>
 * 本类不连数据库、不连 Redis（{@code @WebMvcTest} 切片 + 替身），继承自
 * {@link AbstractSmokeTest} ⇒ 与其它 smoke 用例一样进 Backend job
 * （{@code ci.yml} 里那条不加过滤的 {@code ./mvnw -B test}）。
 */
class PriceTripleFailSmokeTest extends AbstractSmokeTest {

    private static final String URL = "/mysqlGoods/productCodePrice";

    @Test
    @DisplayName("命中：裸行 → {success:true, price, goodImg}（key 映射在搬迁中原样保住）")
    void rawRowBecomesTheOriginalSuccessShape() throws Exception {
        when(goodsCacheService.priceByTriple(anyString(), anyString(), anyString()))
                .thenReturn(row("Price", 9.9, "GoodImg", "/img/a.jpg"));

        mockMvc.perform(post(URL)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // ⚠️ JSON 键是 goodImg（小驼峰），列名是 GoodImg —— 两者**不同名**，
                //    这正是搬迁中最容易顺手「改对」而破坏契约的地方。
                .andExpect(jsonPath("$.goodImg").value("/img/a.jpg"))
                .andExpect(jsonPath("$.price").value(9.9));
    }

    @Test
    @DisplayName("0 行（Service 返回 null）：{success:false}，且不带 price / goodImg")
    void nullMeansNotFoundAndDegradesToTheSameFailureMarker() throws Exception {
        when(goodsCacheService.priceByTriple(anyString(), anyString(), anyString()))
                .thenReturn(null);

        mockMvc.perform(post(URL)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                // 失败体的字段集合必须与改前**逐字段相同**：前端只在 success 为真时读这两个键，
                // 但少一个键与多一个键都属于可观测的契约变化，一并钉住。
                .andExpect(jsonPath("$.price").doesNotExist())
                .andExpect(jsonPath("$.goodImg").doesNotExist());
    }

    @Test
    @DisplayName("★反向断言：Service 抛出技术异常 → 仍是 200 + {success:false}，**不是 500**")
    void manyRowsFailureIsStillHttp200AndNotA500() throws Exception {
        // 故意用「>1 行」那条异常：它是本次唯一**新增**的抛出口（改前 Service 自己吞掉它）。
        when(goodsCacheService.priceByTriple(anyString(), anyString(), anyString()))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        mockMvc.perform(post(URL)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods")
                        .param("ProductCode", "SMOKE-P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.price").doesNotExist())
                .andExpect(jsonPath("$.goodImg").doesNotExist());
    }

    @Test
    @DisplayName("任一形参缺失：仍是 400（@RequestParam 必填，搬迁未动入参约束）")
    void missingParamStaysABadRequest() throws Exception {
        mockMvc.perform(post(URL)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods"))
                .andExpect(status().isBadRequest());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }
}
