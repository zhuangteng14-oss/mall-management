package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.goods.controller.GoodsController;
import com.minke.mallmanagement.message.controller.MessageController;
import org.springframework.http.HttpStatus;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局异常 → 统一响应体适配器（笔 3）。把「入参不合法」这一类失败从 Spring 默认的空响应体
 * 变成笔 2 定的 {@link Result} 平铺契约：{@code {"success":false,"msg":…}}。
 *
 * <h2>【为什么状态码保持 400，不改成 200】</h2>
 * 「形参缺失 ⇒ 400」是**既有且被钉住的契约**：
 * {@code PriceTripleFailSmokeTest.missingParamStaysABadRequest} 的 DisplayName 逐字写着
 * 「任一形参缺失：仍是 400」。
 * <ul>
 *   <li>改 200 会让「同一类失败」在「缺参（400）」与「校验失败（200）」之间分裂；</li>
 *   <li>且要动一条**故意钉住**的断言 —— 那正是防回归的反面。</li>
 * </ul>
 * 故本类只统一 **body**，不碰状态码。附带好处：A3 的 {@code count5xx}
 * （{@code ConcurrencyRunner.java:1097-1105} 只数 {@code code>=500} 或 {@code -1}）
 * 对 400 不可见 ⇒ {@code A-S1-1} / {@code A-S4b-1} 的「5xx == 0」不受影响。
 *
 * <h2>【为什么必须用 {@code assignableTypes} 白名单】</h2>
 * 裸的 {@code @RestControllerAdvice} 会把**所有** Controller 都接管，包括未映射路径的
 * {@code NoResourceFoundException} ⇒ 三条 {@code status().isNotFound()} 断言当场红：
 * {@code PriceTripleFailSmokeTest:121}、{@code GoodsAddUploadFailSmokeTest:79}、
 * {@code ImgMappingSmokeTest:57}。白名单把作用域收到**只有本笔挂了校验的两个 Controller**。
 *
 * <p>⚠️ 白名单是**类级**的 —— 无法按方法排除。故 {@code UserController} /
 * {@code OrderController} **整类不进**，尽管它们的多数端点并无问题：
 * <ul>
 *   <li>{@code UserController} 含 3 个「裸响应体」例外端点（{@code loginSelect} 返回裸
 *       {@code int}、{@code loginAdd} 返回裸 {@code String}、{@code getUserInfo} 失败返回
 *       {@code null}）—— 见 {@link Result} 类注释「例外清单」；</li>
 *   <li>{@code OrderController.orderAdd} 受 D#1 禁令与 B2 锁 tag 约束，**一字不动**；</li>
 *   <li>且这两个类的写端点**本就没有可加的非空校验**：{@code updateUserInfo} 的三个字段
 *       前端会**合法地发空串**（{@code UserInfoPage.vue:154-156} 用 {@code x.value || ''}）
 *       ⇒ 给它加 {@code @NotBlank} 会**当场断前端**。故「排除清单」与「无校验价值」
 *       在这里自动重合 —— 是设计自洽，不是妥协。</li>
 * </ul>
 *
 * <h2>【为什么不注册通用 {@code Exception} 兜底】</h2>
 * 三条理由，逐条可核：
 * <ol>
 *   <li>会把 {@code UserController.loginAdd} 今天**唯一**那处未捕获的 DuplicateKey → 500
 *       变成 200，使 A3 的 {@code A-B2-1} 缺陷信号 {@code b.login5xx}
 *       （{@code ConcurrencyRunner.java:2159}，信号在 {@code :2210}）归零 —— 静默改写已录证据；</li>
 *   <li>Service 层已广泛 {@code catch (Exception e)}，逃逸面很小且已枚举
 *       （{@code OrderState.java:118}、{@code OrderStateMachine.java:296/302/309} 的
 *       {@code IllegalArgumentException}、{@code GoodsCacheService.java:261} 的
 *       {@code IncorrectResultSizeDataAccessException}）⇒ 兜底换来的覆盖面很薄；</li>
 *   <li>它是本笔唯一能引入**状态码漂移**的入口，而 {@code A-S1-1} / {@code A-S4b-1}
 *       对状态码敏感。</li>
 * </ol>
 * 故本类只注册**封闭的异常清单**（下方三个），其余异常按现状冒泡到 Spring 默认处理。
 *
 * <h2>【msg 文本的两条红线】</h2>
 * 本类产出的 {@code msg} 必须永远避开两个子串 —— 它们被 A3 **逐字** count：
 * <ul>
 *   <li>{@code 数据库错误} —— {@code A-S2a-1c} / {@code A-S3-3} 数它（{@code ConcurrencyRunner.java:1110}）；</li>
 *   <li>{@code "success":true}（冒号后无空格）—— {@code Resp.successTrue()} 判据
 *       （{@code :785-787}）。</li>
 * </ul>
 * 下方三条文案均为「参数校验失败：…」「缺少参数：…」「参数类型错误：…」，都安全。
 *
 * <h2>【谁保护本类】</h2>
 * {@code ValidationProbeTest}（{@code @WebMvcTest} 切片，进 Backend job）：
 * 阳性例证明「合法入参不被误拒」，阴性例证明「空串确实被拦且响应体是 Result 形状」。
 * ⚠️ **A3 不是本类的证据** —— A3 从不发空/缺/负值，约束注解在 A3 里零触发
 * （见 {@code ValidationProbeTest} 类注释与 {@code defect-survey.md} 的「A3 空证据」元发现）。
 */
@RestControllerAdvice(assignableTypes = {MessageController.class, GoodsController.class})
public class GlobalExceptionHandler {

    /**
     * 约束注解被触发（{@code @NotBlank} / {@code @Min} / {@code @Max} …）。
     *
     * <p>Spring 6.1+ 对**控制器方法参数**上的约束注解做内建校验，抛本异常 —— 不需要类级
     * {@code @Validated}，故**不产生 CGLIB 代理**（前提已实证：加注解后
     * {@code MallManagementApplicationTests} 的 4 条 {@code xxxControllerIsNotAopProxied}
     * 仍绿；空白入参返回 400、合法入参返回 200）。这一点是本笔能成立的全部关键。
     *
     * <p>实测（2026-10-03，本机 Temurin 17）：未接本处理器时，400 的响应体是**空的** ——
     * 前端 {@code await res.json()} 会抛 {@code SyntaxError}，落进各自的外层 {@code catch}。
     * 本处理器补上可解析的 {@code msg}，是这条路径上唯一的改善。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, Object>> onValidationFailure(HandlerMethodValidationException e) {
        List<String> parts = new ArrayList<>();
        for (ParameterValidationResult r : e.getAllValidationResults()) {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors().forEach(err -> parts.add(name + " " + err.getDefaultMessage()));
        }
        String detail = parts.isEmpty() ? "参数不合法" : String.join("；", parts);
        return Result.fail("参数校验失败：" + detail);
    }

    /**
     * 必填 {@code @RequestParam} 缺失。状态码与响应形状都是既有行为（400），
     * 本处理器只把默认错误体换成 {@link Result}。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, Object>> onMissingParameter(MissingServletRequestParameterException e) {
        return Result.fail("缺少参数：" + e.getParameterName());
    }

    /**
     * 参数在、但类型转不过去（如 {@code PayIf=abc}）。同上：状态码不变，只换 body。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail("参数类型错误：" + e.getName());
    }
}
