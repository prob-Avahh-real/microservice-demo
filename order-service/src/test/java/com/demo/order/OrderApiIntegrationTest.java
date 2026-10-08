package com.demo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.util.Map;

import com.demo.common.ApiResponse;
import com.demo.common.DeductCommand;
import com.demo.common.DeductResult;
import com.demo.order.client.InventoryClient;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * HTTP 层契约测试：把跨服务调用换成替身，验证接口形状与状态码。
 * 真实的跨服务链路由 scripts/e2e.sh 覆盖。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.config.enabled=false",
                "spring.config.import=",
                "demo.order.max-quantity-per-order=20"
        })
@AutoConfigureTestRestTemplate
class OrderApiIntegrationTest {

    /**
     * 用 @MockitoBean **替换**掉 Feign 生成的代理。
     *
     * <p>踩过的坑：不能改成「加一个 @Primary 的替身 bean」——Feign 生成的客户端 bean
     * 本身就是 @Primary，于是容器里出现两个 primary，注入直接失败
     * （more than one 'primary' bean found）。要替换而不是并列。
     */
    @MockitoBean
    private InventoryClient inventoryClient;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void reset() {
        // 替身行为：LAPTOP 永远扣得动，其余 SKU 一律库存不足
        lenient().when(inventoryClient.deduct(any(DeductCommand.class))).thenAnswer(invocation -> {
            DeductCommand command = invocation.getArgument(0);
            if (!"LAPTOP".equals(command.sku())) {
                return ApiResponse.ok(DeductResult.rejected(
                        command.sku(), command.quantity(), 5, "INSUFFICIENT_STOCK"));
            }
            return ApiResponse.ok(DeductResult.success(
                    command.sku(), command.quantity(), 12 - command.quantity()));
        });
        rest.postForEntity("/orders/_reset", null, String.class);
    }

    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        return data;
    }

    private ResponseEntity<Map<String, Object>> postOrder(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/orders", HttpMethod.POST, new HttpEntity<>(json, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    @Test
    @DisplayName("POST /orders 成功 → CREATED 且带 orderNo")
    void placeOrderCreated() {
        ResponseEntity<Map<String, Object>> response = postOrder("{\"sku\":\"LAPTOP\",\"quantity\":2}");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("status")).isEqualTo("CREATED");
        assertThat((String) data.get("orderNo")).startsWith("ORD-");
        assertThat(((Number) data.get("remaining")).intValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("POST /orders 库存不足 → REJECTED，HTTP 仍是 200（业务拒绝不是系统错误）")
    void placeOrderRejected() {
        ResponseEntity<Map<String, Object>> response = postOrder("{\"sku\":\"APPLE\",\"quantity\":2}");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("status")).isEqualTo("REJECTED");
        assertThat(data.get("reason")).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("GET /orders/{orderNo} 查得到刚下的单")
    void findOrderByNo() {
        Map<String, Object> created = dataOf(postOrder("{\"sku\":\"LAPTOP\",\"quantity\":1}"));
        String orderNo = (String) created.get("orderNo");

        ResponseEntity<Map<String, Object>> found = rest.exchange(
                "/orders/" + orderNo, HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });

        assertThat(found.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dataOf(found).get("orderNo")).isEqualTo(orderNo);
    }

    @Test
    @DisplayName("非法数量 → 400 + INVALID_REQUEST（不是 500）")
    void invalidQuantityIsBadRequest() {
        ResponseEntity<Map<String, Object>> response = postOrder("{\"sku\":\"LAPTOP\",\"quantity\":0}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }
}
