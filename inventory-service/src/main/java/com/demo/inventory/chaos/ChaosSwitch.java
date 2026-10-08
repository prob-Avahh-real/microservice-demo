package com.demo.inventory.chaos;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 故障注入开关 —— 存在的唯一理由：让「熔断降级」可被真实触发与验收，而不是靠嘴说。
 *
 * <p>打开后，库存服务的业务接口会先睡 {@code delayMillis} 再抛 503，
 * 于是调用方（order-service）的 Resilience4j 熔断器能真的跳开。
 */
@Component
public class ChaosSwitch {

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicInteger tripCount = new AtomicInteger();

    @Value("${demo.chaos.delay-millis:500}")
    private long delayMillis;

    public boolean enabled() {
        return enabled.get();
    }

    public boolean toggle(boolean on) {
        enabled.set(on);
        if (!on) {
            tripCount.set(0);
        }
        return enabled.get();
    }

    /** 记录一次被注入的故障，供 /chaos 查询，验收时能确认故障真的发生过。 */
    public void recordTrip() {
        tripCount.incrementAndGet();
    }

    /**
     * 业务接口入口处的守卫：开关打开时先延迟再抛 503，让调用方熔断器真的跳开。
     * 做成方法而不是散落在各个 controller 里的 if，是为了只有一处「怎么注入故障」的定义。
     */
    public void guard() {
        if (!enabled.get()) {
            return;
        }
        recordTrip();
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "chaos injection is ON: inventory-service is simulating an outage");
    }

    public int tripCount() {
        return tripCount.get();
    }

    public long delayMillis() {
        return delayMillis;
    }
}
