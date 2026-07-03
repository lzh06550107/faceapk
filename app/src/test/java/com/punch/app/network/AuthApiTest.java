package com.punch.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.punch.app.network.dto.AuthDto;
import com.punch.app.utils.SessionManager;

import org.junit.Test;

import okhttp3.Request;

public class AuthApiTest extends ApiTestSupport {

    @Test
    public void login_shouldUsePublicEndpointWithoutAuthorizationAndParseResponse() throws Exception {
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"token\":\"token-123\"," +
                        "\"expires_in\":604800," +
                        "\"token_expire_at\":1893456000," +
                        "\"device\":{" +
                        "\"device_id\":\"A1B2C3D4E5F60789\"," +
                        "\"name\":\"PDA\"}" +
                        "}"
        ));

        ApiResult<AuthDto.LoginData> result = ApiService.login("admin", "a123456!", "A1B2C3D4E5F60789");

        assertTrue(result.success);
        assertNotNull(result.data);
        assertEquals("token-123", result.data.token);
        assertEquals("A1B2C3D4E5F60789", result.data.deviceId);
        assertEquals("PDA", result.data.deviceName);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/auth/login", request.url().encodedPath());
        assertEquals("POST", request.method());
        assertNull(request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"account\":\"admin\""));
        assertTrue(body.contains("\"password\":\"a123456!\""));
        assertTrue(body.contains("\"device_id\":\"A1B2C3D4E5F60789\""));
    }

    @Test
    public void login_shouldReturnBusinessFailureWhenCodeIsNotSuccess() throws Exception {
        interceptor.enqueueJson(200, "{" +
                "\"code\":401," +
                "\"msg\":\"invalid credentials\"" +
                "}");

        ApiResult<AuthDto.LoginData> result = ApiService.login("admin", "bad-pass", "A1B2C3D4E5F60789");

        assertFalse(result.success);
        assertEquals(401, result.code);
        assertEquals("invalid credentials", result.message);
        assertNull(result.data);
    }

    @Test
    public void refreshToken_shouldSendAuthorizationHeaderAndBodyToken() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"token\":\"token-new\"," +
                        "\"expires_in\":604800," +
                        "\"token_expire_at\":1893457000" +
                        "}"
        ));

        ApiResult<AuthDto.TokenData> result = ApiService.refreshToken("token-abc");

        assertTrue(result.success);
        assertEquals("token-new", result.data.token);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/auth/refresh", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        assertTrue(interceptor.takeBody().contains("\"token\":\"token-abc\""));
    }

    @Test
    public void refreshToken_shouldFailWhenTokenFieldIsMissing() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"expires_in\":604800," +
                        "\"token_expire_at\":1893457000" +
                        "}"
        ));

        ApiResult<AuthDto.TokenData> result = ApiService.refreshToken("token-abc");

        assertFalse(result.success);
        assertEquals(200, result.code);
        assertEquals("success", result.message);
        assertNull(result.data);
    }

    @Test
    public void refreshToken_shouldKeepServerMessageWhenHttpStatusIsNotSuccess() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(401, "{" +
                "\"code\":401," +
                "\"msg\":\"token expired\"" +
                "}");

        ApiResult<AuthDto.TokenData> result = ApiService.refreshToken("token-abc");

        assertFalse(result.success);
        assertEquals(401, result.code);
        assertEquals("token expired", result.message);
        assertNull(result.data);
    }
}
