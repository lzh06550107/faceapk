package com.punch.app.network.dto;


public final class AuthDto {
    private AuthDto() {
    }

    
    public static final class LoginData {
        
        public String token;
        
        public long tokenExpireAt;
        
        public String deviceId = "";
        
        public String deviceName = "";
        
        public String lineCode = "";
        
        public String lineName = "";
        
        public String teamCode = "";
    }

    
    public static final class TokenData {
        
        public String token;
        
        public long tokenExpireAt;
    }
}