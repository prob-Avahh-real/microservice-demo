package com.demo.common;

/**
 * 机器可判定的失败码。E2E 脚本按这些常量断言，不按中文文案断言。
 */
public final class ErrorCodes {

    public static final String OK = "OK";

    /** 库存不足（业务拒绝，上游是健康的）。 */
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    /** SKU 不存在。 */
    public static final String SKU_NOT_FOUND = "SKU_NOT_FOUND";

    /** 参数非法。 */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** 上游服务不可用 / 熔断打开 → 这里才是「降级」，与业务拒绝区分开。 */
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

    /** 上游被故障注入开关打开（用于验证熔断真的会跳）。 */
    public static final String CHAOS_INJECTED = "CHAOS_INJECTED";

    private ErrorCodes() {
    }
}
