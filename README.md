# microservice-demo

Spring Cloud 微服务最小闭环：**服务注册发现 + 统一配置 + API 网关 + 服务间调用 + 熔断降级**，
外加一个 Capacitor 打包的 Android 客户端用于验证链路。

**零外部依赖** —— 不需要 Docker / Nacos / MySQL / Redis。5 个进程都是普通 Spring Boot 应用，
数据存在各自的 H2 内存库里，一条命令就能全起来。

---

## 一、架构

```
                   ┌──────────────────────────────────────────────┐
   Android / curl  │            api-gateway   :8080               │
   ───────────────▶│   Spring Cloud Gateway (响应式)               │
                   │   路由 + CORS + X-Trace-Id 全局过滤           │
                   └───────────────┬──────────────────────────────┘
                          lb://   │   lb://
                ┌─────────────────┴─────────────────┐
                ▼                                   ▼
      ┌───────────────────┐   Feign(服务名)  ┌────────────────────┐
      │  order-service    │─────────────────▶│ inventory-service  │
      │      :8081        │  熔断 + 降级      │       :8082        │
      │  订单 / H2         │                  │  库存 / H2 / 故障注入│
      └───────────────────┘                  └────────────────────┘
                │                                   │
                └───────────┬───────────────────────┘
                            ▼
                 ┌────────────────────┐        ┌────────────────────┐
                 │  eureka-server     │        │  config-server     │
                 │      :8761         │        │       :8888        │
                 │  注册与发现         │        │  统一配置 (native)  │
                 └────────────────────┘        └────────────────────┘
```

| 服务 | 端口 | 职责 |
|------|------|------|
| `config-server` | 8888 | 统一配置中心（native profile，配置随包分发） |
| `eureka-server` | 8761 | 服务注册与发现 |
| `inventory-service` | 8082 | 库存查询 / 扣减 / 故障注入开关 |
| `order-service` | 8081 | 下单编排：Feign 调库存 → 落单；熔断降级 |
| `api-gateway` | 8080 | 统一入口、路由、CORS、traceId |
| `mobile` | — | Capacitor Android 客户端（调网关） |

---

## 二、快速开始

前置：JDK 21、Maven 3.10+。**不需要 Docker。**

```bash
# 1. 编译 + 单测
mvn clean install

# 2. 一键起全套（幂等，已在跑的服务会跳过）
bash scripts/run-all.sh

# 3. 端到端验收（8 项断言，退出码非 0 即失败）
bash scripts/e2e.sh

# 4. 停止
bash scripts/stop-all.sh
```

运行日志在 `.run/logs/`，PID 在 `.run/`。

---

## 三、接口

全部经网关访问（`http://localhost:8080`）。响应统一为 `{ok, code, message, data}`。

| 方法 | 路径（经网关） | 说明 |
|------|----------------|------|
| GET | `/api/inventory/{sku}` | 查库存 |
| GET | `/api/inventory/_list` | 查全部库存 |
| POST | `/api/inventory/deduct` | 扣减库存 `{sku, quantity}` |
| POST | `/api/inventory/chaos?enabled=true\|false` | **故障注入开关**（用于验证熔断） |
| POST | `/api/orders` | 下单 `{sku, quantity}` |
| GET | `/api/orders` | 订单列表 |
| GET | `/api/orders/{orderNo}` | 按订单号查 |

诊断（直连服务端口，不经网关）：

| 路径 | 说明 |
|------|------|
| `:8082/diagnostics/config` | 库存服务当前生效的配置 + 配置源 |
| `:8081/diagnostics/config` | 订单服务当前生效的配置 + 配置源 |

示例：

```bash
curl http://localhost:8080/api/inventory/MONITOR
curl -X POST -H 'Content-Type: application/json' \
     -d '{"sku":"MONITOR","quantity":3}' http://localhost:8080/api/orders
```

---

## 四、三种结果被明确区分（本工程的核心设计）

下单不会只有「成功 / 失败」两种结果，而是三态：

| status | 含义 | 触发条件 | HTTP |
|--------|------|----------|------|
| `CREATED` | 库存扣减成功 | 正常 | 200 |
| `REJECTED` | **业务拒绝**：库存不足 / SKU 不存在 / 参数非法 | 上游是健康的，抛异常是错的 | 200 |
| `DEGRADED` | **技术降级**：上游不可用或熔断已打开 | 由 CircuitBreaker 的 fallback 兜住 | 200 |

关键点：

- 业务拒绝**不计入**熔断失败率（它不是「技术故障」）
- 降级也会落库并写明 `reason` + `errorType`，**绝不假装成功**
- `remaining = -1` 表示「未知」，不用 `0` 冒充已知值

---

## 五、验收（Done 的判据）

`bash scripts/e2e.sh` 真实起 5 个进程后逐条断言，全部对着**行为**而不是日志：

1. 经网关查库存成功（网关 → 注册中心 → 库存服务链路通）
2. 经网关下单 → `CREATED`，剩余量正确
3. 再查库存 → **余额真的减少了**（排除「假成功」）
4. 超量下单 → `REJECTED` + `INSUFFICIENT_STOCK`
5. 15 件放行、25 件被拒 → **单笔上限确实来自配置中心**（本地兜底是 1）
6. 两个服务的属性源里都有 `configserver`，且 PEAR(3 件) 的 `lowStock=true` 与阈值 5 一致
7. 打开故障注入后连续下单 → 5/5 都是 `DEGRADED`，且出现 `CallNotPermittedException`（**熔断器真的打开了**）
8. Eureka 注册表里 3 个服务都在

> 第 5、7 条是刻意设计的「配置中心存在性证明」：
> 本地兜底值被设成「几乎不可能生效」的极端值，
> 于是这些行为只有在配置真的从配置中心下发时才成立。

---

## 六、如何新增一个服务（扩展点）

**核心管线不用改，只加配置：**

1. 复制 `inventory-service/` 的 pom 结构建模块，父 pom 加 `<modules>` 项
2. 在 `config-server/src/main/resources/config-repo/<新服务名>.yml` 放它专属的配置
3. 在 `api-gateway/src/main/resources/application.yml` 的
   `spring.cloud.gateway.server.webflux.routes` 里加一条：
   ```yaml
   - id: payment-service
     uri: lb://payment-service
     predicates:
       - Path=/api/payments/**
     filters:
       - StripPrefix=1
   ```

服务间调用一律写**服务名**（`@FeignClient(name = "...")`），禁止硬编码 `host:port`。
`common/` 模块只放对外契约（DTO / 错误码 / 状态枚举），不放实现。

---

## 七、版本基准

| 组件 | 版本 |
|------|------|
| JDK | 21 |
| Spring Boot | **4.0.8** |
| Spring Cloud | **2025.1.3** |
| Eureka 组件 | 5.0.2 |
| Gateway | 5.0.3（`spring-cloud-starter-gateway-server-webflux`） |

> Spring Boot 4.0.8 这个数字不是随便挑的：它写在 `spring-cloud-dependencies:2025.1.3`
> 这份 BOM 自己的 `<spring-boot.version>` 里。两个版本必须成对升级。
>
> 另外注意 Spring Cloud 5.x 的两个改名坑：`spring-cloud-starter-gateway` 已不存在
> （拆成了 `-server-webflux` / `-server-webmvc`），网关配置前缀变成了
> `spring.cloud.gateway.server.webflux.*`。写错前缀不会报错，只是「一条路由都没有」。

---

## 八、工程取舍

| 决定 | 理由 | 代价 |
|------|------|------|
| 用 H2 内存库 | 零外部依赖，一条命令可复现 | 重启即丢数据 |
| Config Server 用 `classpath:` 配置源 | 不依赖启动时的工作目录 | 改配置要重新打包（无热更新） |
| 业务服务用 Servlet(Web MVC)，网关用 WebFlux | 网关用响应式是 Spring Cloud Gateway 的经典形态 | 两套编程模型并存 |
| 熔断用编程式 `CircuitBreakerFactory` | 别名字典式注解的包路径在 Cloud 2025.x 有变动；编程式 API 自 2020 稳定且好单测 | 代码比注解略长 |
| 故障注入开关只作用于写接口 | 否则打开了就关不掉 | — |
| 每个 JVM `-Xmx320m` | 这台机器 8GB，5 个默认堆会把机器压垮 | 只适合 demo 负载 |

---

## 九、已知限制

- Eureka 关闭了自我保护（`enable-self-preservation: false`），这是 demo 选择，生产要打开
- 库存扣减没有分布式事务保证：`order-service` 落单失败时不会回滚 `inventory-service` 的扣减
  （需要 TCC/Saga/本地消息表，属于后续里程碑）
- 配置不支持热更新（见上表）
- 没有鉴权，网关对所有来源开放（CORS 允许所有 origin）
- 观测只有 actuator + 日志，没有接入 Prometheus / 链路追踪后端
