package com.punch.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.punch.app.utils.SessionManager;

import org.junit.Test;

import okhttp3.Request;

public class EventApiTest extends ApiTestSupport {

    @Test
    public void reportEventResult_shouldSendFailurePayloadAndReturnSuccess() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope("{}"));

        ApiResult<Void> result = ApiService.reportEventResult(
                "evt_001",
                "person_changed",
                false,
                "db write failed"
        );

        assertTrue(result.success);
        assertNull(result.data);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/event/result", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"device_id\":\"A1B2C3D4E5F60789\""));
        assertTrue(body.contains("\"event_cursor\":\"evt_001\""));
        assertTrue(body.contains("\"event_type\":\"person_changed\""));
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("\"failure_msg\":\"db write failed\""));
    }

    @Test
    public void reportEventResult_shouldOmitFailureMessageWhenBlank() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope("{}"));

        ApiResult<Void> result = ApiService.reportEventResult(
                "evt_002",
                "config_changed",
                true,
                "   "
        );

        assertTrue(result.success);

        String body = afterSingleRequestBody(ApiEndpoints.EVENT_RESULT);
        assertTrue(body.contains("\"success\":true"));
        assertFalse(body.contains("\"failure_msg\""));
    }
}
