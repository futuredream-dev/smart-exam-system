package com.smartexam.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void shouldCreateDocumentedSuccessResponse() {
        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("操作成功");
        assertThat(response.getErrorCode()).isNull();
        assertThat(response.getData()).isEqualTo("ok");
    }

    @Test
    void shouldCreateDocumentedFailureResponse() {
        ApiResponse<Void> response = ApiResponse.failure("无权执行该操作", "FORBIDDEN");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("无权执行该操作");
        assertThat(response.getErrorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.getData()).isNull();
    }

    @Test
    void shouldSerializeDocumentedSuccessJson() throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(ApiResponse.success(Map.of())));

        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("message").asText()).isEqualTo("操作成功");
        assertThat(json.has("errorCode")).isFalse();
        assertThat(json.get("data").isObject()).isTrue();
    }

    @Test
    void shouldSerializeDocumentedFailureJson() throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(
                        ApiResponse.failure("无权执行该操作", "FORBIDDEN")));

        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("message").asText()).isEqualTo("无权执行该操作");
        assertThat(json.get("errorCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(json.get("data").isNull()).isTrue();
    }
}
