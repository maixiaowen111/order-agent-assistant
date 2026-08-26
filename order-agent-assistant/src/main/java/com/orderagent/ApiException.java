package com.orderagent;

/**
 * 业务 API 调用失败的统一分类。
 * errorCode —— 给开发人员看的分类号（如 HTTP_TIMEOUT / CONNECTION_FAILED / BUSINESS_ERROR）；
 * message   —— 给模型/用户看的人话，不暴露任何内部细节。
 * 目的：让"哪里挂了"可被分类、可被翻译，而不是把 Java 底层异常原话喂给模型。
 */
public class ApiException extends RuntimeException {

    private final String errorCode;

    public ApiException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
