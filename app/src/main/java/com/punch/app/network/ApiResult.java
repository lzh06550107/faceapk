package com.punch.app.network;


public final class ApiResult<T> {
    
    public final boolean success;
    
    public final int code;
    
    public final String message;
    
    public final T data;

    private ApiResult(boolean success, int code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message == null ? "" : message;
        this.data = data;
    }

    
    public static <T> ApiResult<T> success(int code, String message, T data) {
        return new ApiResult<>(true, code, message, data);
    }

    
    public static <T> ApiResult<T> failure(int code, String message) {
        return new ApiResult<>(false, code, message, null);
    }
}