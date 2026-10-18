package com.cuijian.digital.common;

/**
 * 业务异常。
 * 和系统异常分开：业务异常是「用户操作不合法」，要原样把原因返回给前端；
 * 系统异常统一吞掉细节，避免把堆栈和表结构暴露出去。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
