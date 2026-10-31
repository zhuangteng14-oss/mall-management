package com.minke.mallmanagement.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码（2026-10-31「上线前扫雷」）：生成 4 位字符图片 + 服务端一次性校验。
 *
 * <h2>【存在哪】</h2>
 * 首选 Redis：键 {@code auth:captcha:<uuid>}，TTL {@value #TTL_MINUTES} 分钟，值 = 验证码明文
 * （用户能看见它，不是秘密派生值）。<b>Redis 不可用时退回进程内表</b>（同 TTL）——
 * 本地演示环境没有 Redis，验证码必须照样能用；单实例下两者语义等价。
 * 生产多实例部署必须让 Redis 可用，否则 A 机发的码 B 机验不过。
 *
 * <h2>【一次性】</h2>
 * {@link #verifyAndConsume} 无论对错都**先删后比**：错一次就必须重新发一张
 * （用户要求「错了一次就失效」）。删与比不在一个事务里 —— Redis 侧先 GET 再 DEL，
 * 并发重放最多只有一发能通过，最坏情况是「发了两次、第二次被拒」，对验证码是可接受的方向。
 *
 * <h2>【图上不出现易混字符】</h2>
 * 字母表剔掉 {@code 0/O/1/I}，避免用户眼睁睁看着正确却总说错。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    /** 验证码有效期（分钟）。 */
    public static final int TTL_MINUTES = 5;

    private static final String KEY_PREFIX = "auth:captcha:";
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Redis 不可用时的进程内兜底（键 = captchaId）。 */
    private final ConcurrentHashMap<String, MemoryEntry> memory = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 一次挑战：给前端的是 id 与 data URL，验证码明文只留在服务端。 */
    public record Challenge(String id, String image) {
    }

    /** 生成一张新验证码。永远不抛（Redis 故障时自动退回内存表）。 */
    public Challenge issue() {
        String id = UUID.randomUUID().toString().replace("-", "");
        String code = randomCode();
        store(id, code);
        return new Challenge(id, render(code));
    }

    /**
     * 校验并**消费**验证码。{@code false} = 没这张 / 已过期 / 不匹配 —— 三种情形前端
     * 都只需「刷新重试」，不区分（区分会给出可探测信号）。
     */
    public boolean verifyAndConsume(String id, String input) {
        if (id == null || id.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String expected = loadAndDelete(id.trim());
        return expected != null && expected.equalsIgnoreCase(input.trim());
    }

    // ==================== 存储 ====================

    private void store(String id, String code) {
        try {
            redis.opsForValue().set(KEY_PREFIX + id, code, Duration.ofMinutes(TTL_MINUTES));
            return;
        } catch (DataAccessException e) {
            log.warn("验证码写入 Redis 失败，退回进程内存储：{}", e.getMessage());
        }
        purgeExpired();
        memory.put(id, new MemoryEntry(code, System.currentTimeMillis() + TTL_MINUTES * 60_000L));
    }

    private String loadAndDelete(String id) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + id);
            redis.delete(KEY_PREFIX + id);
            if (value != null) {
                return value;
            }
        } catch (DataAccessException e) {
            log.warn("验证码读取 Redis 失败，改查进程内存储：{}", e.getMessage());
        }
        MemoryEntry entry = memory.remove(id);
        if (entry == null) {
            return null;
        }
        return entry.expiresAt() >= System.currentTimeMillis() ? entry.code() : null;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        memory.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private record MemoryEntry(String code, long expiresAt) {
    }

    // ==================== 生成 ====================

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 画一张 120×40 的 PNG，返回 {@code data:image/png;base64,…}（前端直接塞 {@code <img src>}）。 */
    private static String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线：颜色与背景同族，不盖住字符
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(150 + RANDOM.nextInt(80), 160 + RANDOM.nextInt(70), 180 + RANDOM.nextInt(60)));
                g.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT),
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }
            // 干扰点
            for (int i = 0; i < 40; i++) {
                g.setColor(new Color(120 + RANDOM.nextInt(100), 120 + RANDOM.nextInt(100), 140 + RANDOM.nextInt(90)));
                g.fillOval(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), 2, 2);
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            int step = WIDTH / (LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(20 + RANDOM.nextInt(70), 40 + RANDOM.nextInt(70), 80 + RANDOM.nextInt(90)));
                double angle = (RANDOM.nextDouble() - 0.5) * 0.5;
                int x = step / 2 + i * step;
                int y = 29 + RANDOM.nextInt(5) - 2;
                g.rotate(angle, x, y);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.rotate(-angle, x, y);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            // 画不出来属 JDK 环境问题（headless 字体缺失等）；宁可拒绝登录，也不放行无验证码
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }
}
