# 里程碑与验收台账（状态外置 / Loop 原则 5）

> 本文件是**唯一进度真相**。每完成一个里程碑就在这里改状态，禁止只靠聊天记忆。

**工程：** Spring Cloud 微服务最小闭环 + Android 客户端
**基准日期：** 2026-10-08

---

## 版本基准（Baseline，已对 Maven Central 实测，非猜测）

| 组件 | 版本 | 依据 |
|------|------|------|
| JDK | **21**（`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`） | 需显式指定：`brew` 装的 maven 默认跑 JDK 27 |
| Maven | 3.10.0（Homebrew） | `mvn -v` |
| Spring Boot | **4.0.8** | `spring-cloud-dependencies:2025.1.3` 的 BOM 内声明 `<spring-boot.version>4.0.8</spring-boot.version>` |
| Spring Cloud | **2025.1.3** | Maven Central 上 `spring-cloud-dependencies` 最新 release |
| Eureka 组件 | 5.0.2 | BOM 内 `spring-cloud-netflix.version=5.0.2` |
| Gateway | 5.0.3（`spring-cloud-starter-gateway-server-webflux`） | BOM `spring-cloud-gateway.version=5.0.3` |
| Config | 5.0.5 | BOM `spring-cloud-config.version=5.0.5` |
| Feign / LoadBalancer / CircuitBreaker | 5.0.3 | BOM 各对应 version 属性 |
| 数据库 | H2 内存库 | 零外部依赖 |
| 外部中间件 | **无**（不用 Docker / Nacos / MySQL / Redis） | 用户选定 |

---

## 踩坑清单（全部是本机实测踩到的，写下来避免重复调试）

### A. 版本坐标类

1. Initializr 页面把 Boot 版本显示成 `4.1.1.RELEASE`，**该坐标在 Maven Central 不存在**（404）；
   真实坐标是 `4.1.1` / `4.0.8`（无 `.RELEASE` 后缀）。
2. Initializr 会把 Boot 4.1.1 与 Cloud 2025.1.3 配对，但 Cloud 2025.1.3 的 BOM 自述基线是 **Boot 4.0.8**。
   取 BOM 自述值，避免版本漂移。
3. `spring-cloud-starter-gateway` 在 5.x **已不存在**（404），拆成
   `spring-cloud-starter-gateway-server-webflux`（经典响应式）与 `-server-webmvc`（Servlet）。
   同理 `spring-cloud-gateway-server` 也停在 4.3.5。

### B. Spring Boot 4 的重命名（会静默失败或编译不过）

4. **网关配置前缀**从 `spring.cloud.gateway.routes` 变成
   `spring.cloud.gateway.server.webflux.routes`（含 `globalcors` 等）。
   **写错不报错**，只是「一条路由都没有」——所以有 `GatewayRoutesTest` 专门钉住这点。
5. **测试支持按 Web 栈拆包**：`spring-boot-starter-test` 只剩核心（JUnit/Mockito/AssertJ）；
   Servlet 应用要 `spring-boot-starter-webmvc-test`，响应式要用 `spring-boot-starter-webflux-test`。
   **网关绝不能用 webmvc-test**，它会把 servlet 栈拖进测试类路径，Gateway 的 WebFlux 自动配置失效。
6. `TestRestTemplate` 换包：`org.springframework.boot.test.web.client` →
   **`org.springframework.boot.resttestclient`**，且需 `@AutoConfigureTestRestTemplate`。
7. `TestRestTemplate` 的自动配置需要 `RestTemplateBuilder`，它在 `spring-boot-restclient` 里，
   而 `spring-boot-starter-web` **已不再包含**它 → 需额外加 `spring-boot-starter-restclient`(test)。
8. Feign 生成的客户端 bean **本身就是 `@Primary`**。测试里用「再加一个 `@Primary` 替身 bean」会
   得到 `more than one 'primary' bean found` → 必须用 `@MockitoBean` **替换**而不是并列。
9. `CircuitBreakerFactory` 的 `run(Supplier, Function)` 是**泛型方法，lambda 实现不了**，替身要写匿名类。
10. `Resilience4JCircuitBreakerFactory` 在 5.0.3 **没有无参构造**（javap 确认需 Registry×2 + BulkheadProvider）。

### C. 本机环境类（最花时间的一类）

11. **Clash 的 TUN 接口会拦掉「连本机 LAN IP」的回连** —— 这是本次最隐蔽的坑：
    `curl 192.168.3.34:8082` **超时**（不是拒绝），而 `curl localhost:8082` 正常。
    服务若用 `eureka.instance.prefer-ip-address: true` 注册成 `192.168.3.34`，
    网关就永远连不上后端，表现为路由 503 / 空响应。**本工程改为注册 `localhost`**。
    排查手法：开 `-Dlogging.level.com.netflix.discovery=DEBUG`，看到
    `initial instances count: 3` 说明注册表拿到了、问题在 LB 之后的连接；
    再直连 LAN IP 复现超时即可定位。本机还有 Tailscale（100.97.73.31）与 5 个 utun 接口。
12. **这台 Mac 只有 bash 3.2**（macOS 自带，没有 brew bash）。bash 3.2 **不支持关联数组**：
    `declare -A` 会报 `-A: invalid option`，随后 `[config-server]=8888` 被当**算术下标**解析，
    在 `set -u` 下报 `config: unbound variable`。脚本里用 `case` 函数代替关联数组。
13. Docker **守护进程没在跑**、`docker-compose` 插件二进制损坏（exec format error）、
    Docker Hub 直连不通 → 选「零外部依赖」路线是对的。
14. Maven Central 直连可用（~1.2s），**不需要**配镜像或代理；但 `dl.google.com` / `maven.google.com`
    / `docker.io` 需走 Clash 代理（`http://127.0.0.1:7897`）。Android 构建要用到这条。
15. Android SDK 在 `/opt/homebrew/share/android-commandlinetools`（platforms/android-34、build-tools/34.0.0），
    **不是** `~/Library/Android/sdk`（项目里 `local.properties` 曾指向那个不存在的路径）。
16. 5 个 JVM 必须限堆（`-Xmx320m` + SerialGC）：这台机器 8GB，默认堆（1/4 RAM × 5）会压垮机器。

### D. 自己写错的（不是版本问题的）

17. `patch` 与 `write_file` 用混了：对 `order-service/application.yml` 本想做局部替换，
    却用了 `write_file`（整文件覆盖）→ 配置被截断成只剩 `demo:` 段，
    启动报 `No spring.config.import property has been defined`。**改已有文件一律用 `patch`。**
18. 单测里用 `quantity=99` 验「库存不足」，但 `max-deduct-per-request=50` 会先把它判成
    `INVALID_REQUEST` → 测试没验到想验的东西。**测试数据要避开前置校验**（改用 4 件）。
19. `run-all.sh` 里 `(( fail == 0 )) && wait_eureka_apps ...` 的返回值被丢弃，
    导致「网关路由不通」时脚本仍打印「全套已就绪」并 `exit 0`（假成功）。
    改成显式 `if ... then wait_... || fail=1; fi`。
20. **bash 3.2 会把紧跟变量的多字节字符吃进变量名**：`"...= $before（链路..."` 里的
    `$before（` 被解析成变量 `before<乱码>` → `unbound variable`。
    中文文案里必须写成 `${before}（`。**这是 bash 3.2 的老 bug，仅用 `$var` 会随机炸。**
21. `grep -o '<name>[^<]*</name>'` 解析 Eureka **XML** 会把 `dataCenterInfo` 里的
    `<name>MyOwn</name>`（表示 "my own datacenter"）当成第 4 个应用名。
    断言要走 **JSON API** 只取 `applications.application[].name`。
    —— 教训：断言「读数错了但看着通过」比直接失败更危险。
22. 断言里的字符串比较要注意 Eureka 应用名是**大写**（`INVENTORY-SERVICE`），
    用 `tr '[:lower:]' '[:upper:]'` 统一后再比，否则误报「注册表里缺服务」。

---

## 里程碑

### M0 · 脚手架与依赖解析
- **交付物：** 父 pom + 6 个模块
- **验收：** `mvn -q clean package -DskipTests` 退出码 0
- **状态：** ✅ **完成** — `mvn -B clean install` BUILD SUCCESS，6 个模块全部出 jar

### M1 · 注册中心 + 统一配置中心
- **交付物：** `eureka-server`(:8761)、`config-server`(:8888, native)
- **验收：** Config Server 返回配置；Eureka 有注册表
- **状态：** ✅ **完成** — 实测 `curl :8761/eureka/apps` 返回 3 个应用；
  `:8082/diagnostics/config` 的 `configServerPropertySourcePresent=true`

### M2 · 业务服务（Feign + 熔断 + H2）
- **交付物：** `inventory-service`(:8082)、`order-service`(:8081)
- **验收：** `mvn test` 全绿，覆盖「下单成功 / 库存不足 / 上游不可用降级」
- **状态：** ✅ **完成** — **22 个测试全绿**
  （inventory 10 = 4 集成 + 6 单测；order 10 = 4 集成 + 6 单测；gateway 2 = 路由加载）

### M3 · 网关打通 + 端到端链路（核心 Done）
- **交付物：** `api-gateway`(:8080)、`scripts/run-all.sh` / `stop-all.sh` / `e2e.sh`
- **验收（硬指标）：** `bash scripts/e2e.sh` 真实起 5 个进程后 8 项断言全 PASS
- **状态：** ✅ **完成** — **8/8 断言全部 PASS，退出码 0**（连跑两次稳定通过，脚本可重复执行）
  - 关掉故障注入后等 11s 让熔断器转半开，保证 e2e 可重复跑
  - 关键一项目击证据：第 7 条观察到 `CallNotPermittedException`，
    说明熔断器不是「配了但没跳」，而是真的打开了

### M4 · Android 客户端（Capacitor）
- **交付物：** `mobile/` Capacitor 工程 + `android/`，产出 `app-debug.apk`
- **验收：** `gradlew assembleDebug` 出 APK；网关地址可配置
- **状态：** ⬜ 未开始（依赖 M3 通过）

---

## 明确定义「完成」（Done）

`bash scripts/e2e.sh` 输出 8/8 PASS 且退出码 0；`mvn test` 全绿；`mobile/android` 产出 APK。
任一条未达成即视为未完成，不允许「看着还行」。

## 停止条件

- 成功：M0–M3 全绿 → 进入 M4
- 上限：同一模块连续失败 3 次即停下来重估方案（而不是继续堆补丁）
- 人审：需要改基准版本 / 引入外部中间件 / 改验收标准时，必须先问用户
