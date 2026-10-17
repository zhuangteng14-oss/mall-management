package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 块 7 commit 3 · 三个 SSE 端点的 smoke test：
 * {@code POST /admin/observe/{metrics,orders,events}/stream} 的**路由、动词、首帧格式**。
 *
 * <h2>【本类钉的是什么】</h2>
 * <ol>
 *   <li><b>路由与动词真的挂上了</b>（不是 404）：三条都是 {@code POST}；</li>
 *   <li><b>响应真的带 SSE 的媒体类型</b>：{@code Content-Type: text/event-stream}。
 *       ⚠️ 这一条**不是**框架白送的：{@code @PostMapping(produces=...)} 对
 *       {@code StreamingResponseBody} 这样的异步返回**不写响应头**，三个 Controller 都在
 *       {@code stream(...)} 里手动设了它（实测：不设时响应头里只有 {@code Cache-Control}）
 *       ⇒ 本断言真的在看着一行**我们自己写的**代码；</li>
 *   <li><b>首帧的线格式对</b>：体以 {@code data: } 开头、以空行（{@code \n\n}）结尾，
 *       且中间那一段是**单行** JSON（帧内没有裸换行 —— {@code SseStreamWriter} 类注释那条不变量）；</li>
 *   <li><b>流真的会结束</b>（在本 profile 下）：靠 {@code mall.observe.sse.max-ticks=1}
 *       （见 {@code src/test/resources/application-smoke.properties}）—— 推满一帧即正常收工。
 *       没有这一条，本类会**挂住**（不是失败，是等一条永不结束的流）。</li>
 * </ol>
 *
 * <h2>【⚠️ 本类**不**钉什么（这条边界比断言本身重要）】</h2>
 * <b>不断帧里的数值</b>。切片里：{@code JdbcTemplate} 是 {@code @MockitoBean}（SQL 不出切片、
 * 只读查询一律回空），{@code MeterRegistry} 是 {@code SimpleMeterRegistry}（**没有任何真实流量**）。
 * ⇒ 断言「QPS = 0」等于断言「Mockito 与内存注册表的默认值」，改对改错都是绿的
 * （同 {@code StatsSmokeTest} 那条 ⚠️ 的姿势）。本类只断**键存在**、**帧格式对**、
 * **计数与事件数组是同一次产帧的两面**这类「不依赖库内容」的性质。
 *
 * <p>真正的数值正确性（差分、命中率、五态判据链）归单测：
 * {@code observe/service/*Test}（不起 Spring、不起 MySQL，替身喂死数据，断言可复核）。
 */
class ObserveStreamSmokeTest extends AbstractSmokeTest {

    @Test
    @DisplayName("指标流：POST 通、Content-Type=text/event-stream、首帧是单行 JSON 的 data 帧")
    void metricsStreamPushesOneFrame() throws Exception {
        String body = callStream("/admin/observe/metrics/stream");
        assertThat(body).contains("\"requests\"").contains("\"cache\"")
                .contains("\"protections\"").contains("\"stock\"");
        assertThat(body).contains("\"seq\":0").as("首帧的 seq 从 0 起（第一拍）");
    }

    @Test
    @DisplayName("订单流：POST 通、首帧带五态计数 + 订单列表 + 流转数组")
    void orderStreamPushesOneFrame() throws Exception {
        String body = callStream("/admin/observe/orders/stream");
        assertThat(body).contains("\"counts\"").contains("\"orders\"").contains("\"moves\"");
    }

    @Test
    @DisplayName("事件流：POST 通、首帧带 8 类计数（8 个键一个不少）与事件数组")
    void eventStreamPushesOneFrame() throws Exception {
        String body = callStream("/admin/observe/events/stream");
        assertThat(body).contains("\"events\"").contains("\"severity\"");
        // 8 类（顺序 = EventBroadcaster.Kind 的声明顺序）—— 少一个就说明枚举与帧对不上了。
        // ⚠️ 只断「键在」、不断数值：counts 跨测试累计（同一份切片上下文；CI run 39055832261 实测 stock=19）。
        assertThat(body).contains("\"cache\":").contains("\"lock\":").contains("\"order\":")
                .contains("\"stock\":").contains("\"audit\":").contains("\"notify\":")
                .contains("\"sensitive\":").contains("\"identity\":");
    }

    /**
     * 打一条流并返回**一帧**的正文（本 profile 下 max-ticks=1 ⇒ 正文就是那一帧）。
     *
     * <p>{@code StreamingResponseBody} 是异步返回 ⇒ 必须先断 {@code asyncStarted}、
     * 再 {@code asyncDispatch} 把那一帧取回来（顺序反了拿到的是空体）。三条共用一个方法：
     * 它们除了路径与帧内容之外**逐字相同**，这正是本类要证明的那部分（路线 + 动词 + 帧格式）。
     *
     * <p>⚠️ 这里必须写全 {@code MockMvcResultMatchers.request()}：本类**继承**了基类的
     * {@code request(MockHttpServletRequestBuilder)}（加鉴权头的统一出口），
     * 而 Java 的规则是**类里继承来的方法压过静态导入** —— 静态导入 {@code request} 在这里
     * 会被解析成那个单参方法，报「参数列表长度不同」。同理，{@code post(path)} 用的是
     * 基类那个方法（**正是我们要的**：端点要跟其它请求走同一条出口）。
     */
    private String callStream(String path) throws Exception {
        MvcResult pending = mockMvc.perform(post(path))
                .andExpect(MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();
    }
}
