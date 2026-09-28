package com.fastcms.plugin.ai.subscription.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * 授权码编解码与签名校验。
 *
 * <p><b>授权码格式</b>：{@code FCAI1.<Base64URL(载荷)>.<Base64URL(签名)>}</p>
 *
 * <h3>为什么用 Ed25519 非对称签名</h3>
 * <ul>
 *   <li><b>绝不能用 HMAC</b>：客户实例本地只能持有验签材料。若用对称密钥，客户实例里就躺着签发密钥，
 *       任何人反编译后都能自行签发任意期限的授权码，授权等于不存在。非对称签名下客户侧只有公钥，
 *       公开分发不构成风险。</li>
 *   <li>Ed25519 由 JDK 15+ 原生支持，无需引入 BouncyCastle；签名固定 64 字节，比 RSA 短得多，
 *       授权码长度可控。</li>
 *   <li>签名覆盖"前缀 + 分隔符 + 载荷"，因此前缀或载荷任一被改都会验签失败，
 *       "改个版本号绕过校验"这条路也被堵死。</li>
 * </ul>
 *
 * <h3>为什么载荷不用 JSON</h3>
 * <ul>
 *   <li>授权码是展示给用户复制粘贴的字符串，JSON 的引号、大括号、重复的键名纯属浪费
 *       （约 127 字符 → 约 74 字符）。</li>
 *   <li>更重要的是<b>摘掉安全关键路径上的 JSON 库耦合</b>：插件运行在 PF4J 插件类加载器下，
 *       依赖由主程序提供。fastcms 主程序在 Spring Boot 4 下用的是 Jackson 3
 *       （{@code tools.jackson}），而 Jackson 2（{@code com.fasterxml}）只是 runtime 传递依赖，
 *       编译期并不可见；fastjson 又老旧且带 autotype 反序列化风险。字段固定且全部是标量，
 *       自己解析反而更可控、更可测。</li>
 * </ul>
 *
 * <p>载荷字段顺序固定，以 {@code ~} 分隔（{@code ~} 不在 Base64URL 字符集内，人工核对授权码时
 * 不会与编码字符混淆）：</p>
 *
 * <pre>
 * v ~ sid ~ plan ~ scope(逗号分隔) ~ iat ~ exp ~ mch
 * 1 ~ sub-1001 ~ year ~ template,article ~ 1732700000 ~ 1764236000 ~
 * </pre>
 *
 * @author wjun_java@163.com
 */
public final class LicenseCodec {

    /** 授权码前缀，同时承载格式版本语义 */
    public static final String CODE_PREFIX = "FCAI1";

    /** 载荷格式版本 */
    public static final int PAYLOAD_VERSION = 1;

    /** Ed25519 签名固定长度（字节） */
    private static final int SIGNATURE_LENGTH = 64;

    private static final String SEPARATOR = ".";
    private static final char FIELD_SEPARATOR = '~';
    private static final String FIELD_SEPARATOR_TEXT = String.valueOf(FIELD_SEPARATOR);
    private static final int FIELD_COUNT = 7;
    private static final String SCOPE_SEPARATOR = ",";

    private static final String SIGN_ALGORITHM = "Ed25519";
    private static final String KEY_ALGORITHM = "Ed25519";

    /** 授权码内一律用 URL-safe Base64 且去掉 padding：不含 {@code + / =}，放进 URL、剪贴板都不会出问题 */
    private static final Base64.Encoder CODE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CODE_DECODER = Base64.getUrlDecoder();

    /** 密钥用标准 Base64（与 openssl 输出一致，便于运维用 openssl 生成/查看） */
    private static final Base64.Encoder KEY_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder KEY_DECODER = Base64.getDecoder();

    private LicenseCodec() {
    }

    // ------------------------------------------------------------------ 签发（官网侧）

    /**
     * 用私钥签发授权码。
     *
     * @param payload    授权载荷
     * @param privateKey 官网持有的 Ed25519 私钥
     * @return 授权码字符串
     * @throws IllegalStateException 载荷含非法字符（如 sid 里带分隔符）导致无法编码时
     */
    public static String issue(LicensePayload payload, PrivateKey privateKey) {
        Objects.requireNonNull(payload, "payload 不能为空");
        Objects.requireNonNull(privateKey, "privateKey 不能为空");

        String body = CODE_ENCODER.encodeToString(writePayload(payload));
        String signingInput = CODE_PREFIX + SEPARATOR + body;
        try {
            byte[] signature = sign(signingInput, privateKey);
            return signingInput + SEPARATOR + CODE_ENCODER.encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("授权码签名失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 校验（客户侧）

    /**
     * 验签并解析授权码。
     *
     * <p>本方法<b>保证不抛异常</b> —— 任何异常都转成对应的失败状态，便于直接用在接口里回话。</p>
     *
     * @param code      用户粘贴的授权码
     * @param publicKey 内置的验签公钥
     * @return 校验结果；{@link LicenseVerifyResult#status()} 为 {@code OK} 时
     *         {@link LicenseVerifyResult#payload()} 才可信
     */
    public static LicenseVerifyResult verify(String code, PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey 不能为空");

        String normalized = normalize(code);
        if (normalized == null) {
            return LicenseVerifyResult.badFormat("授权码不能为空");
        }

        String[] parts = normalized.split("\\" + SEPARATOR);
        if (parts.length != 3) {
            return LicenseVerifyResult.badFormat("授权码格式不正确，应为三段：FCAI1.载荷.签名");
        }

        if (!CODE_PREFIX.equals(parts[0])) {
            if (parts[0].startsWith("FCAI")) {
                return LicenseVerifyResult.unsupportedVersion("不支持的授权码版本：" + parts[0]);
            }
            return LicenseVerifyResult.badFormat("授权码前缀无法识别，请确认已复制完整内容");
        }

        byte[] payloadBytes;
        try {
            payloadBytes = CODE_DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return LicenseVerifyResult.badFormat("授权码载荷不是合法的 Base64URL，可能复制时有缺失");
        }

        byte[] signature;
        try {
            signature = CODE_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return LicenseVerifyResult.badFormat("授权码签名不是合法的 Base64URL，可能复制时有缺失");
        }

        if (signature.length != SIGNATURE_LENGTH) {
            return LicenseVerifyResult.badSignature();
        }

        // 先验签、再解析载荷：未通过验签的载荷一个字段都不能信
        try {
            if (!verifySignature(parts[0] + SEPARATOR + parts[1], signature, publicKey)) {
                return LicenseVerifyResult.badSignature();
            }
        } catch (GeneralSecurityException e) {
            return LicenseVerifyResult.badSignature();
        }

        LicensePayload payload;
        try {
            payload = readPayload(payloadBytes);
        } catch (IllegalArgumentException e) {
            return LicenseVerifyResult.badFormat("授权码载荷无法解析：" + e.getMessage());
        }

        if (payload.v() != PAYLOAD_VERSION) {
            return LicenseVerifyResult.unsupportedVersion("授权码载荷版本不受支持：" + payload.v());
        }

        if (payload.exp() <= Instant.now().getEpochSecond()) {
            return LicenseVerifyResult.expired(payload);
        }

        return LicenseVerifyResult.ok(payload);
    }

    // ------------------------------------------------------------------ 载荷编解码

    private static byte[] writePayload(LicensePayload payload) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(payload.v()).append(FIELD_SEPARATOR);
        sb.append(requireNoSeparator(payload.sid(), "sid")).append(FIELD_SEPARATOR);
        sb.append(requireNoSeparator(payload.plan(), "plan")).append(FIELD_SEPARATOR);
        sb.append(joinScope(payload.scope())).append(FIELD_SEPARATOR);
        sb.append(payload.iat()).append(FIELD_SEPARATOR);
        sb.append(payload.exp()).append(FIELD_SEPARATOR);
        sb.append(requireNoSeparator(nullToEmpty(payload.mch()), "mch"));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static LicensePayload readPayload(byte[] bytes) {
        // limit=-1：保留末尾空字段（mch 为空时那种情况），否则字段数会少一个
        String[] fields = new String(bytes, StandardCharsets.UTF_8).split(FIELD_SEPARATOR_TEXT, -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("载荷字段数不正确，期望 " + FIELD_COUNT + " 个，实际 " + fields.length + " 个");
        }
        return new LicensePayload(
                parseInt(fields[0], "v"),
                nullIfEmpty(fields[1]),
                nullIfEmpty(fields[2]),
                splitScope(fields[3]),
                parseLong(fields[4], "iat"),
                parseLong(fields[5], "exp"),
                nullIfEmpty(fields[6]));
    }

    private static String joinScope(List<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String feature : scope) {
            String token = requireNoSeparator(nullToEmpty(feature).trim(), "scope 项");
            if (token.indexOf(SCOPE_SEPARATOR.charAt(0)) >= 0) {
                throw new IllegalArgumentException("scope 项不能包含 '" + SCOPE_SEPARATOR + "'：" + token);
            }
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SCOPE_SEPARATOR);
            }
            sb.append(token);
        }
        return sb.toString();
    }

    private static List<String> splitScope(String field) {
        List<String> scope = new ArrayList<>();
        if (field == null || field.isBlank()) {
            return scope;
        }
        StringTokenizer tokenizer = new StringTokenizer(field, SCOPE_SEPARATOR);
        while (tokenizer.hasMoreTokens()) {
            String token = tokenizer.nextToken().trim();
            if (!token.isEmpty()) {
                scope.add(token);
            }
        }
        return scope;
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 不是合法整数：" + value);
        }
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 不是合法时间戳：" + value);
        }
    }

    /**
     * 官网签发侧校验：字段值不得包含分隔符。
     *
     * <p>选择"签发时直接报错"而不是"静默转义"，是因为签发方是官网自己，出现非法字符一定是
     * 配置或数据问题；静默处理会让授权码内容与预期不一致，排查成本更高。</p>
     */
    private static String requireNoSeparator(String value, String field) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(FIELD_SEPARATOR) >= 0) {
            throw new IllegalArgumentException(field + " 不能包含 '" + FIELD_SEPARATOR + "'：" + value);
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullIfEmpty(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ 密钥辅助

    /** 生成一对 Ed25519 密钥（官网初始化时的运维操作） */
    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(KEY_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("生成 Ed25519 密钥对失败：" + e.getMessage(), e);
        }
    }

    /** 公钥编码为 Base64（X.509），用于内置到客户实例 / 展示给运维 */
    public static String encodePublicKey(PublicKey publicKey) {
        return KEY_ENCODER.encodeToString(publicKey.getEncoded());
    }

    /** 私钥编码为 Base64（PKCS#8） */
    public static String encodePrivateKey(PrivateKey privateKey) {
        return KEY_ENCODER.encodeToString(privateKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64) {
        Objects.requireNonNull(base64, "公钥不能为空");
        try {
            byte[] bytes = KEY_DECODER.decode(base64.trim());
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥无法解析，请确认是 Ed25519 的 X.509 公钥 Base64：" + e.getMessage(), e);
        }
    }

    public static PrivateKey decodePrivateKey(String base64) {
        Objects.requireNonNull(base64, "私钥不能为空");
        try {
            byte[] bytes = KEY_DECODER.decode(base64.trim());
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥无法解析，请确认是 Ed25519 的 PKCS#8 私钥 Base64：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private static byte[] sign(String signingInput, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(SIGN_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signature.sign();
    }

    private static boolean verifySignature(String signingInput, byte[] signature, PublicKey publicKey)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    /**
     * 归一化用户输入：去除所有空白。
     *
     * <p>Base64URL 字符集与分隔符都不含空白，整体去掉是安全的；而粘贴授权码时极易带上换行/空格
     * （从网页、聊天工具、邮件里复制都会），不处理会直接被判成格式错误。</p>
     */
    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.replaceAll("\\s+", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
