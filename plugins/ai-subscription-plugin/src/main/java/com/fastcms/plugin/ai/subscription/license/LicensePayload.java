package com.fastcms.plugin.ai.subscription.license;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 授权码载荷：授权码中携带的全部业务信息，由官网签发时写入、官网私钥签名保护。
 *
 * <p>字段刻意用短名（{@code sid} / {@code exp} 等）以缩短授权码长度 —— 授权码最终是要
 * 展示给用户复制粘贴的字符串，每字节都有体验成本。</p>
 *
 * @param v     载荷格式版本，当前为 {@link LicenseCodec#PAYLOAD_VERSION}
 * @param sid   订阅 id（官网侧溯源用，对应 {@code ai_subscription_grant.sub_id}）
 * @param plan  订阅周期标识，见 {@link SubscriptionPlan}
 * @param scope 功能范围：{@code template}（AI 模板编辑）/ {@code article}（AI 文章编写）/
 *              {@code image}（AI 生图）。<b>空表示全部开放</b> —— 这是官网自己签发的字段，
 *              不存在被第三方利用的空间，因此取宽松默认，避免官网漏填导致客户侧功能全废
 * @param iat   签发时间（epoch 秒）
 * @param exp   到期时间（epoch 秒）
 * @param mch   绑定的机器指纹，{@code null} / 空表示不限制
 * @author wjun_java@163.com
 */
public record LicensePayload(
        int v,
        String sid,
        String plan,
        List<String> scope,
        long iat,
        long exp,
        String mch
) {

    /** 功能范围常量：AI 模板编辑 */
    public static final String SCOPE_TEMPLATE = "template";
    /** 功能范围常量：AI 文章编写 */
    public static final String SCOPE_ARTICLE = "article";
    /** 功能范围常量：AI 生图 */
    public static final String SCOPE_IMAGE = "image";

    /** 订阅周期枚举，无法识别时为 {@code null} */
    public SubscriptionPlan planEnum() {
        return SubscriptionPlan.of(plan);
    }

    public Instant issuedInstant() {
        return Instant.ofEpochSecond(iat);
    }

    public Instant expireInstant() {
        return Instant.ofEpochSecond(exp);
    }

    public LocalDateTime issuedAt() {
        return LocalDateTime.ofInstant(issuedInstant(), ZoneId.systemDefault());
    }

    public LocalDateTime expireAt() {
        return LocalDateTime.ofInstant(expireInstant(), ZoneId.systemDefault());
    }

    public Set<String> scopeSet() {
        return scope == null ? Set.of() : Set.copyOf(scope);
    }

    /**
     * 判断本授权是否覆盖某项功能。
     *
     * @param feature 功能标识，如 {@link #SCOPE_TEMPLATE}
     * @return scope 为空视为全部开放，否则要求包含该功能
     */
    public boolean allows(String feature) {
        Set<String> set = scopeSet();
        return set.isEmpty() || set.contains(feature);
    }

    /** 是否已到期（以当前系统时间为准） */
    public boolean expired() {
        return exp <= Instant.now().getEpochSecond();
    }

    /** 是否绑定了机器指纹 */
    public boolean machineBound() {
        return mch != null && !mch.isBlank();
    }
}
