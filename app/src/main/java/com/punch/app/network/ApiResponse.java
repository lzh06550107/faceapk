package com.punch.app.network;

import com.google.gson.JsonElement;


public class ApiResponse {
    
    public final boolean success;
    
    public final int code;
    
    public final String message;
    
    public final JsonElement data;

    public ApiResponse(boolean success, int code, String message, JsonElement data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }
}