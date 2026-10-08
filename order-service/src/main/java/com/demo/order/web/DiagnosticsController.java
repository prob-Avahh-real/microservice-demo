package com.demo.order.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.demo.common.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 配置自证接口：把「配置中心是否真的生效」变成可断言的事实。 */
@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {

    private final Environment environment;

    @Value("${demo.order.max-quantity-per-order:1}")
    private int maxQuantityPerOrder;

    @Value("${demo.info.config-fingerprint:local}")
    private String configFingerprint;

    public DiagnosticsController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        boolean fromConfigServer = false;
        String matchedSource = "none";
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source.getName().toLowerCase().contains("configserver")) {
                    fromConfigServer = true;
                    matchedSource = source.getName();
                    break;
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", environment.getProperty("spring.application.name"));
        body.put("activeProfiles", List.of(environment.getActiveProfiles()));
        body.put("maxQuantityPerOrder", maxQuantityPerOrder);
        body.put("configFingerprint", configFingerprint);
        body.put("configServerPropertySourcePresent", fromConfigServer);
        body.put("configServerPropertySource", matchedSource);
        return ApiResponse.ok(body);
    }
}
