package com.demo.common;

/**
 * 统一响应外壳。所有服务对外都返回这个形状，失败必须带明确 code——
 * 不允把失败伪装成成功（Harness 原则 6：可观测失败）。
 */
public record ApiResponse<T>(boolean ok, String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, ErrorCodes.OK, "success", data);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data);
    }
}
