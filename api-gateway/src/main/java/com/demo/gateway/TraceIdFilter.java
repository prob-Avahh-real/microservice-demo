package com.demo.gateway;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 全局过滤器：给每个请求打一个 traceId，回写到响应头并出现在访问日志里。
 * 入口处有 traceId，跨服务排查才有共同的锚点（可观测性）。
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        String path = exchange.getRequest().getURI().getRawPath();
        String method = exchange.getRequest().getMethod().name();
        ServerHttpRequest mutated = exchange.getRequest().mutate().header(TRACE_HEADER, traceId).build();
        exchange.getResponse().getHeaders().add(TRACE_HEADER, traceId);

        final String tid = traceId;
        long start = System.nanoTime();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doOnSuccess(ignored -> log.info("{} {} -> {} traceId={} cost={}ms",
                        method, path,
                        exchange.getResponse().getStatusCode() == null ? "?" : exchange.getResponse().getStatusCode().value(),
                        tid, (System.nanoTime() - start) / 1_000_000))
                .doOnError(error -> log.warn("{} {} -> ERROR traceId={} : {}",
                        method, path, tid, error.toString()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
