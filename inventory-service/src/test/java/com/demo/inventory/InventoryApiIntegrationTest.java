package com.demo.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 只在本地起本服务（不拉配置中心、不注册 Eureka），验证 HTTP 层的契约形状。
 * 真正的跨服务链路由 scripts/e2e.sh 覆盖——那是更强的传感器。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.config.enabled=false",
                "spring.config.import=",
                "spring.cloud.config.request-connect-timeout=100",
                "spring.cloud.config.request-read-timeout=100"
        })
@AutoConfigureTestRestTemplate
class InventoryApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    @DisplayName("GET /inventory/{sku} 返回 ApiResponse 外壳 + 数据")
    void queryReturnsWrappedPayload() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/inventory/APPLE", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> b = body(response);
        assertThat(b.get("ok")).isEqualTo(true);
        assertThat(b.get("code")).isEqualTo("OK");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) b.get("data");
        assertThat(data.get("sku")).isEqualTo("APPLE");
        assertThat(((Number) data.get("available")).intValue()).isEqualTo(100);
    }

    @Test
    @DisplayName("POST /inventory/deduct 真的扣减库存（幂等断言：查一次少一次）")
    void deductActuallyMutatesStock() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"sku\":\"LAPTOP\",\"quantity\":2}", headers);

        ResponseEntity<Map<String, Object>> deductResponse = rest.exchange(
                "/inventory/deduct", HttpMethod.POST, request,
                new ParameterizedTypeReference<>() {
                });

        assertThat(deductResponse.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body(deductResponse).get("data");
        assertThat(data.get("success")).isEqualTo(true);
        assertThat(((Number) data.get("remaining")).intValue()).isEqualTo(10);

        ResponseEntity<Map<String, Object>> queryResponse = rest.exchange(
                "/inventory/LAPTOP", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) body(queryResponse).get("data");
        assertThat(((Number) after.get("available")).intValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("库存不足返回 ok=true 但 success=false + 明确 reason（区分业务拒绝与系统失败）")
    void insufficientStockIsBusinessRejection() {
        // PEAR 只有 3 件；下 4 件在单次扣减上限 (50) 以内，
        // 这样拦下它的**只能是库存逻辑**，而不是参数校验——两者要能分清
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"sku\":\"PEAR\",\"quantity\":4}", headers);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/inventory/deduct", HttpMethod.POST, request,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body(response).get("data");
        assertThat(data.get("success")).isEqualTo(false);
        assertThat(data.get("reason")).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("故障注入打开后 /inventory/deduct 返回 5xx（熔断可被真实触发）")
    void chaosInjectionCausesFailure() {
        rest.postForEntity("/inventory/chaos?enabled=true", null, String.class);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>("{\"sku\":\"APPLE\",\"quantity\":1}", headers);

            ResponseEntity<String> response = rest.exchange(
                    "/inventory/deduct", HttpMethod.POST, request, String.class);

            assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        } finally {
            rest.postForEntity("/inventory/chaos?enabled=false", null, String.class);
        }
    }
}
