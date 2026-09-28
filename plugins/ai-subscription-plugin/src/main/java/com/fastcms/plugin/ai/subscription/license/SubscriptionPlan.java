package com.fastcms.plugin.ai.subscription.license;

import java.time.LocalDateTime;

/**
 * 订阅周期：官网售卖的三档（月度 / 季度 / 年度）。
 *
 * <p>到期时间用 {@link LocalDateTime#plusMonths(long)} 推算，而不是固定天数 ——
 * 商业上"买一年"应当得到对年的同一天而不是 365 天，用户直觉与对账口径都更一致。</p>
 *
 * @author wjun_java@163.com
 */
public enum SubscriptionPlan {

    MONTH("month", 1, "月度"),
    QUARTER("quarter", 3, "季度"),
    YEAR("year", 12, "年度");

    private final String code;
    private final int months;
    private final String label;

    SubscriptionPlan(String code, int months, String label) {
        this.code = code;
        this.months = months;
        this.label = label;
    }

    /** 授权码载荷中记录的周期标识 */
    public String code() {
        return code;
    }

    /** 一个周期包含的月数 */
    public int months() {
        return months;
    }

    /** 中文展示名 */
    public String label() {
        return label;
    }

    /**
     * 从周期标识解析枚举。
     *
     * @param code 周期标识，大小写与首尾空白容错
     * @return 匹配的枚举，无法识别时返回 {@code null}（由调用方决定是拒绝还是降级）
     */
    public static SubscriptionPlan of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (SubscriptionPlan plan : values()) {
            if (plan.code.equals(normalized)) {
                return plan;
            }
        }
        return null;
    }

    /**
     * 以给定时间为起点推算到期时间。
     *
     * @param from 起点（官网签发时间 / 续期时的原到期时间）
     * @return 到期时间
     */
    public LocalDateTime plusTo(LocalDateTime from) {
        return from.plusMonths(months);
    }
}
