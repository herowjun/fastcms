package com.fastcms.plugin.ai.subscription.license;

import com.fastcms.plugin.ai.subscription.license.LicenseVerifyResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权码编解码与验签的回归测试。
 *
 * <p>这个类保护的是整套订阅体系的安全边界：只要"改载荷续命"或"拿别人的码"能被放过一次，
 * 授权就等于不存在。因此篡改类用例都是<b>真实构造被改过的授权码再走一遍验签</b>，
 * 而不是断言一个 mock 的返回值。</p>
 */
class LicenseCodecTest {

    private static final long DAY = 86400L;
    private static final String FS = "~";

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private KeyPair keyPair;
    private PublicKey publicKey;
    private PrivateKey privateKey;

    @BeforeEach
    void setUp() {
        keyPair = LicenseCodec.generateKeyPair();
        publicKey = keyPair.getPublic();
        privateKey = keyPair.getPrivate();
    }

    // ------------------------------------------------------------------ 基本往返

    @Test
    void issueThenVerifyReturnsOkWithSameFields() {
        long now = Instant.now().getEpochSecond();
        long exp = now + 365 * DAY;
        LicensePayload payload = new LicensePayload(LicenseCodec.PAYLOAD_VERSION, "sub-1001", "year",
                List.of(LicensePayload.SCOPE_TEMPLATE, LicensePayload.SCOPE_ARTICLE), now - 60, exp, null);

        String code = LicenseCodec.issue(payload, privateKey);

        assertTrue(code.startsWith("FCAI1."), "授权码应以 FCAI1. 开头，实际：" + code);
        assertEquals(2, code.chars().filter(c -> c == '.').count(), "授权码应恰好有两处分隔符");

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.OK, result.status());
        assertTrue(result.ok());
        assertEquals("", result.message());

        LicensePayload actual = result.payload();
        assertEquals(LicenseCodec.PAYLOAD_VERSION, actual.v());
        assertEquals("sub-1001", actual.sid());
        assertEquals("year", actual.plan());
        assertEquals(SubscriptionPlan.YEAR, actual.planEnum());
        assertEquals(exp, actual.exp());
        assertEquals(now - 60, actual.iat());
        assertEquals(List.of(LicensePayload.SCOPE_TEMPLATE, LicensePayload.SCOPE_ARTICLE), actual.scope());
        assertNull(actual.mch());
        assertFalse(actual.machineBound());
    }

    @Test
    void payloadTextFormatIsStable() {
        // 授权码是长期凭证：已签发出去的码必须在未来版本仍能验签通过，
        // 因此载荷的文本格式用黄金值钉死，格式一改这条就会红
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-9", "month", List.of("template"), 1000L, 2000L, null), privateKey);

        String payloadText = new String(URL_DECODER.decode(code.split("\\.")[1]), StandardCharsets.UTF_8);

        assertEquals("1~sub-9~month~template~1000~2000~", payloadText);
    }

    @Test
    void licenseCodeStaysShortEnoughToPaste() {
        String code = LicenseCodec.issue(new LicensePayload(1, "sub-20260928-0001", "year",
                List.of("template", "article", "image"), 1732700000L, 1764236000L, null), privateKey);

        // 这是给用户复制粘贴的字符串，长度得有上限意识（JSON 编码同样内容会多 50 字符左右）
        assertTrue(code.length() < 200, "授权码过长，实际 " + code.length() + " 字符：" + code);
    }

    // ------------------------------------------------------------------ 安全边界：篡改

    @Test
    void payloadTamperedToExtendExpiryIsRejected() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-2002", "month", List.of(), now - 60, now + DAY, null), privateKey);

        // 把 exp 改到 100 年后，签名保持原样 —— 最直接的"白嫖"尝试
        String tampered = rewritePayload(code, text -> {
            String[] fields = split(text);
            fields[5] = String.valueOf(now + 100L * 365 * DAY);
            return String.join(FS, fields);
        });

        assertNotEquals(code, tampered, "篡改后的码应当与原码不同");
        LicenseVerifyResult result = LicenseCodec.verify(tampered, publicKey);

        assertEquals(Status.BAD_SIGNATURE, result.status());
        assertNull(result.payload(), "验签未通过的载荷一个字段都不应返回");
    }

    @Test
    void payloadTamperedToUpgradePlanIsRejected() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-2003", "month", List.of(), now - 60, now + DAY, null), privateKey);

        String tampered = rewritePayload(code, text -> {
            String[] fields = split(text);
            fields[2] = "year";
            fields[3] = "template,article,image";
            return String.join(FS, fields);
        });

        assertEquals(Status.BAD_SIGNATURE, LicenseCodec.verify(tampered, publicKey).status());
    }

    @Test
    void tamperedSignatureIsRejected() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-2004", "month", List.of(), now - 60, now + DAY, null), privateKey);

        String[] parts = code.split("\\.");
        byte[] signature = URL_DECODER.decode(parts[2]);
        signature[0] ^= 0x01; // 翻转一个字节
        String tampered = parts[0] + "." + parts[1] + "." + URL_ENCODER.encodeToString(signature);

        assertEquals(Status.BAD_SIGNATURE, LicenseCodec.verify(tampered, publicKey).status());
    }

    @Test
    void codeSignedByForeignKeyIsRejected() {
        long now = Instant.now().getEpochSecond();
        KeyPair foreign = LicenseCodec.generateKeyPair();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-2005", "year", List.of(), now - 60, now + DAY, null), foreign.getPrivate());

        // 本机公钥验不过……
        assertEquals(Status.BAD_SIGNATURE, LicenseCodec.verify(code, publicKey).status());
        // ……换成对端公钥则能通过，说明失败原因确实是"不是我这个签发方签的"
        assertEquals(Status.OK, LicenseCodec.verify(code, foreign.getPublic()).status());
    }

    @Test
    void truncatedCodeIsNeverAccepted() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-2006", "year", List.of(), now - 60, now + DAY, null), privateKey);

        for (int cut : new int[]{1, 5, 20, 64}) {
            String truncated = code.substring(0, code.length() - cut);
            LicenseVerifyResult result = LicenseCodec.verify(truncated, publicKey);
            assertNotEquals(Status.OK, result.status(), "截断 " + cut + " 个字符后不应通过校验");
        }
    }

    @Test
    void validlySignedButMalformedPayloadIsRejectedAsBadFormat() throws Exception {
        // 用合法私钥签一份"字段数不对"的载荷，模拟签发方将来改了格式而客户侧还是旧版
        long now = Instant.now().getEpochSecond();
        String malformed = "1~sub-1~month~template~1000~" + (now + DAY) + "~x~extra";
        String code = signRaw(malformed, privateKey);

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.BAD_FORMAT, result.status());
        assertNull(result.payload());
        assertTrue(result.message().contains("载荷字段数"), "提示应指出字段数问题，实际：" + result.message());
    }

    // ------------------------------------------------------------------ 签发侧校验

    @Test
    void issueRejectsFieldValuesContainingSeparator() {
        long now = Instant.now().getEpochSecond();

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.issue(
                new LicensePayload(1, "sub~evil", "month", List.of(), now, now + DAY, null), privateKey),
                "sid 含分隔符应在签发时报错，而不是产出内容不符的码");

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.issue(
                new LicensePayload(1, "sub-1", "month", List.of("temp~late"), now, now + DAY, null), privateKey));

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.issue(
                new LicensePayload(1, "sub-1", "month", List.of("template,article"), now, now + DAY, null), privateKey),
                "单个 scope 项里混入逗号会造成语义歧义，应报错");
    }

    // ------------------------------------------------------------------ 过期

    @Test
    void expiredCodeReportsExpiredButStillExposesExpiry() {
        long now = Instant.now().getEpochSecond();
        long exp = now - 10 * DAY;
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-3001", "month", List.of(), now - 40 * DAY, exp, null), privateKey);

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.EXPIRED, result.status());
        assertFalse(result.ok());
        // 过期时仍要能拿到载荷，否则没法给用户展示"已于某日到期"
        assertTrue(result.hasPayload());
        assertEquals(exp, result.payload().exp());
        assertTrue(result.message().contains("续费"), "过期提示应引导续费，实际：" + result.message());
    }

    // ------------------------------------------------------------------ 格式容错

    @Test
    void nullAndBlankInputsAreBadFormat() {
        for (String input : new String[]{null, "", "   ", "\n\t "}) {
            LicenseVerifyResult result = LicenseCodec.verify(input, publicKey);
            assertEquals(Status.BAD_FORMAT, result.status(), "输入=" + input);
            assertNull(result.payload());
        }
    }

    @Test
    void malformedCodesAreBadFormat() {
        String[] malformed = {
                "abc",
                "FCAI1",
                "FCAI1.",
                "FCAI1.abc",
                "FCAI1.@@@.###",
                "FCAI1.###.@@@",
                "FCAI1.a.b.c"
        };
        for (String input : malformed) {
            assertEquals(Status.BAD_FORMAT, LicenseCodec.verify(input, publicKey).status(), "输入=" + input);
        }
    }

    @Test
    void decodableButWrongLengthSignatureIsBadSignature() {
        // abc / def 都是合法 Base64URL（各解出 2 字节），但签名长度远不足 64 字节
        assertEquals(Status.BAD_SIGNATURE, LicenseCodec.verify("FCAI1.abc.def", publicKey).status());
    }

    @Test
    void unsupportedCodePrefixIsDistinguishedFromGarbage() {
        // FCAI 开头但版本号不认识 —— 属可识别的版本问题，提示应区别于"复制错了"
        assertEquals(Status.UNSUPPORTED_VERSION, LicenseCodec.verify("FCAI9.abc.def", publicKey).status());
        assertEquals(Status.BAD_FORMAT, LicenseCodec.verify("FCMS1.abc.def", publicKey).status());
    }

    @Test
    void pastedCodeWithLineBreaksAndSpacesStillVerifies() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-4001", "quarter", List.of(), now - 60, now + DAY, null), privateKey);

        // 模拟从网页/聊天工具复制时被折行、两端带空白
        String messy = "  " + code.substring(0, 12) + "\r\n" + code.substring(12, 40) + " \t"
                + code.substring(40) + "  \n";

        assertTrue(messy.contains("\n") && messy.contains("\t"), "构造的输入应确实包含空白");
        assertEquals(Status.OK, LicenseCodec.verify(messy, publicKey).status());
    }

    // ------------------------------------------------------------------ 功能范围

    @Test
    void emptyOrNullScopeMeansAllFeaturesAllowed() {
        long now = Instant.now().getEpochSecond();
        long exp = now + DAY;

        LicensePayload empty = new LicensePayload(1, "s", "month", List.of(), now - 60, exp, null);
        LicensePayload nullScope = new LicensePayload(1, "s", "month", null, now - 60, exp, null);

        assertTrue(empty.allows(LicensePayload.SCOPE_TEMPLATE));
        assertTrue(empty.allows(LicensePayload.SCOPE_ARTICLE));
        assertTrue(empty.allows(LicensePayload.SCOPE_IMAGE));
        assertTrue(nullScope.allows(LicensePayload.SCOPE_IMAGE));
        assertEquals(Set.of(), nullScope.scopeSet());
    }

    @Test
    void scopeRestrictsUnauthorizedFeature() {
        long now = Instant.now().getEpochSecond();
        LicensePayload onlyTemplate = new LicensePayload(1, "s", "month",
                List.of(LicensePayload.SCOPE_TEMPLATE), now - 60, now + DAY, null);

        assertTrue(onlyTemplate.allows(LicensePayload.SCOPE_TEMPLATE));
        assertFalse(onlyTemplate.allows(LicensePayload.SCOPE_ARTICLE));
        assertFalse(onlyTemplate.allows(LicensePayload.SCOPE_IMAGE));
    }

    @Test
    void nullScopeIsEncodedAsEmptyFieldAndStillVerifies() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-5001", "month", null, now - 60, now + DAY, null), privateKey);

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.OK, result.status());
        assertEquals(List.of(), result.payload().scope());
        assertTrue(result.payload().allows(LicensePayload.SCOPE_ARTICLE));
    }

    // ------------------------------------------------------------------ 机器绑定

    @Test
    void machineFieldSurvivesRoundTrip() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(new LicensePayload(1, "sub-6001", "quarter", List.of(),
                now - 60, now + DAY, "machine-abc"), privateKey);

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.OK, result.status());
        assertEquals("machine-abc", result.payload().mch());
        assertTrue(result.payload().machineBound());
    }

    @Test
    void blankMachineIsNormalizedToNull() {
        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(new LicensePayload(1, "sub-6002", "month", List.of(),
                now - 60, now + DAY, "   "), privateKey);

        LicenseVerifyResult result = LicenseCodec.verify(code, publicKey);

        assertEquals(Status.OK, result.status());
        assertNull(result.payload().mch(), "空白机器码应归一化为 null，而不是参与绑定比对");
        assertFalse(result.payload().machineBound());
    }

    // ------------------------------------------------------------------ 周期语义

    @Test
    void planMonthsAndExpiryArithmetic() {
        assertEquals(1, SubscriptionPlan.MONTH.months());
        assertEquals(3, SubscriptionPlan.QUARTER.months());
        assertEquals(12, SubscriptionPlan.YEAR.months());
        assertEquals("月度", SubscriptionPlan.MONTH.label());

        LocalDateTime start = LocalDateTime.of(2026, 1, 31, 10, 0);
        // 加月遇到不存在的日期会收敛到当月最后一天，这是 plusMonths 的语义，也是商业上可接受的
        assertEquals(LocalDateTime.of(2026, 2, 28, 10, 0), SubscriptionPlan.MONTH.plusTo(start));
        assertEquals(LocalDateTime.of(2024, 2, 29, 10, 0),
                SubscriptionPlan.MONTH.plusTo(LocalDateTime.of(2024, 1, 31, 10, 0)));
        assertEquals(LocalDateTime.of(2026, 4, 30, 10, 0), SubscriptionPlan.QUARTER.plusTo(start));
        assertEquals(LocalDateTime.of(2027, 1, 31, 10, 0), SubscriptionPlan.YEAR.plusTo(start));
    }

    @Test
    void planOfIsTolerantAndReturnsNullForUnknown() {
        assertEquals(SubscriptionPlan.MONTH, SubscriptionPlan.of("MONTH"));
        assertEquals(SubscriptionPlan.QUARTER, SubscriptionPlan.of("  quarter "));
        assertEquals(SubscriptionPlan.YEAR, SubscriptionPlan.of("year"));
        assertNull(SubscriptionPlan.of("weekly"));
        assertNull(SubscriptionPlan.of(""));
        assertNull(SubscriptionPlan.of(null));
    }

    // ------------------------------------------------------------------ 密钥编解码

    @Test
    void keyEncodingRoundTripsAndDecodedKeysRemainUsable() {
        String publicText = LicenseCodec.encodePublicKey(keyPair.getPublic());
        String privateText = LicenseCodec.encodePrivateKey(keyPair.getPrivate());

        PublicKey decodedPublic = LicenseCodec.decodePublicKey(publicText);
        PrivateKey decodedPrivate = LicenseCodec.decodePrivateKey(privateText);

        long now = Instant.now().getEpochSecond();
        String code = LicenseCodec.issue(
                new LicensePayload(1, "sub-7001", "month", List.of(), now - 60, now + DAY, null), decodedPrivate);

        assertEquals(Status.OK, LicenseCodec.verify(code, decodedPublic).status());
    }

    @Test
    void malformedKeyMaterialFailsWithClearMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodePublicKey("not-a-valid-key"));
        assertTrue(error.getMessage().contains("公钥无法解析"), "异常应说明是公钥解析问题，实际：" + error.getMessage());

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decodePrivateKey("not-a-valid-key"));
    }

    // ------------------------------------------------------------------ 工具

    /** 把授权码的载荷部分替换掉而保留原签名 —— 用来真实构造"被篡改的授权码" */
    private String rewritePayload(String code, UnaryOperator<String> mutate) {
        String[] parts = code.split("\\.");
        String payloadText = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        String body = URL_ENCODER.encodeToString(mutate.apply(payloadText).getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + body + "." + parts[2];
    }

    /** 用给定私钥对任意载荷文本签名（测试桩，用来构造"签名合法但内容非法"的码） */
    private static String signRaw(String payloadText, PrivateKey privateKey) throws Exception {
        String body = URL_ENCODER.encodeToString(payloadText.getBytes(StandardCharsets.UTF_8));
        String signingInput = "FCAI1." + body;
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + URL_ENCODER.encodeToString(signature.sign());
    }

    /** 按分隔符拆载荷字段，保留末尾空字段 */
    private static String[] split(String payloadText) {
        return payloadText.split(FS, -1);
    }
}
