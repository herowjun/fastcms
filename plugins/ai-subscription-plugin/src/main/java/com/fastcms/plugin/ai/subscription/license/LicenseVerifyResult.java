package com.fastcms.plugin.ai.subscription.license;

/**
 * 授权码校验结果。
 *
 * <p>刻意区分失败原因，而不是只给一个 boolean —— 用户粘贴授权码后需要知道
 * "到底是过期要续费、还是复制漏了字符、还是拿错了别人的码"，运维排查也依赖这个区分。</p>
 *
 * @param status  校验状态
 * @param payload 验签通过并解析出的载荷；签名未通过时为 {@code null}（未验签的数据一律不返回，避免被误用）
 * @param message 面向用户的失败说明，成功时为空串
 * @author wjun_java@163.com
 */
public record LicenseVerifyResult(Status status, LicensePayload payload, String message) {

    public enum Status {
        /** 有效 */
        OK,
        /** 签名有效但已过期 */
        EXPIRED,
        /** 格式非法（空、段数不对、Base64 解不开、JSON 解析失败） */
        BAD_FORMAT,
        /** 签名校验不通过（被篡改，或不是本签发方签发的码） */
        BAD_SIGNATURE,
        /** 授权码 / 载荷版本不受支持 */
        UNSUPPORTED_VERSION
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** 是否拿到了可信载荷（过期时仍然有载荷，可用于展示"已于 X 到期"） */
    public boolean hasPayload() {
        return payload != null;
    }

    static LicenseVerifyResult ok(LicensePayload payload) {
        return new LicenseVerifyResult(Status.OK, payload, "");
    }

    static LicenseVerifyResult expired(LicensePayload payload) {
        return new LicenseVerifyResult(Status.EXPIRED, payload, "授权已到期，请续费后重新激活");
    }

    static LicenseVerifyResult badFormat(String message) {
        return new LicenseVerifyResult(Status.BAD_FORMAT, null, message);
    }

    static LicenseVerifyResult badSignature() {
        return new LicenseVerifyResult(Status.BAD_SIGNATURE, null,
                "授权码签名校验不通过，可能被篡改或并非由本站签发");
    }

    static LicenseVerifyResult unsupportedVersion(String message) {
        return new LicenseVerifyResult(Status.UNSUPPORTED_VERSION, null, message);
    }
}
