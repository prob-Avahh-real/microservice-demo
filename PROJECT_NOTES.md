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
23. 坑 20（bash 3.2 多字节变量名）**出现第二次**后，按本仓库棘轮规则不再靠文档提醒，
    而是做成 **Sensor**：`scripts/lint-sh.sh` —— 扫全部 `*.sh`，同时跑 `bash -n`，
    一旦有 `$var` 紧跟中文字符就报错并给出应改成 `${var}` 的位置。
    加完立刻在 `mobile/scripts/build-apk.sh` 里又抓出 2 处（错误分支上的 `$SDK_DIR（`、`$status）`）。
    → **教训：同一种失误出现第二次，就补传感器，不是再写一条注意事项。**
24. 用 `node --test` 时别传目录：`node --test test/` 在 Node 22 会被当成模块路径而报
    `Cannot find module`。用 shell 展开的通配：`node --test test/*.test.js`。
25. 「业务拒绝」与「请求非法」的 HTTP 语义要分清，否则测试会写错断言：
    - `quantity <= 0`（`@Min(1)` 校验失败）→ **400** + `code=INVALID_REQUEST`
    - 超出业务上限（`> 20`，来自配置中心）→ **200** + `status=REJECTED`
    我最初在 App 链路测试里把后者也断言成 400，结果 `Missing expected rejection` 失败 —— 是断言错，不是代码错。
26. **Eureka 的 `/eureka/apps` 是「读缓存快照」，默认每 30s 才刷新一次**
    （`eureka.server.response-cache-update-interval-ms`）。于是会出现：
    **服务注册明明成功（日志里 `registration status: 204`、进程也活着），但注册表里暂时查不到** ——
    这让我卡了很久：网关注册成功了、路由也全通（第 1–7 条断言都过了），
    只有「注册表里应该有 3 个」这一条随机失败。
    正确处理有两层：
    ① demo 里把 `response-cache-update-interval-ms` 调到 **2000**，让注册表接近实时；
    ② 更重要的是**断言要轮询**（e2e 第 8 条改成最多轮询 30s）——
       注册表收敛本来就是最终一致，**对最终一致的读路径拍单张快照，本身就是错误的测试写法**。
    → 教训：断言失败时先问「是不是我把最终一致的东西当成强一致的读了」。
27. **Gradle 自己的 HTTP 客户端走 Clash 代理会 TLS 握手失败**
    （`Remote host terminated the handshake`），而 curl 与 JDK 的 `HttpClient`
    走同一个代理都正常（实测 200）→ 说明是 Gradle 客户端的问题，不是代理坏了。
    应对：
    ① Maven Central / Gradle 服务直连就通 → 放进 `http.nonProxyHosts` 绕开代理；
    ② **Google Maven 直连被墙** → 改用直连可达的国内镜像
       （腾讯 `mirrors.cloud.tencent.com/nexus/repository/maven-public` 最快，0.7s；阿里次之）；
    ③ Capacitor 自带子工程（`node_modules/@capacitor/android/capacitor/build.gradle` 与
       `capacitor-cordova-android-plugins/build.gradle`）**各自带 buildscript{ repositories{ google() } }**，
       根 `build.gradle` 的镜像影响不到它 → 必须用 `mobile/scripts/patch-capacitor-repos.cjs`
       把镜像插到**列表最前面**。注意超时是「失败」而不是「找不到」，
       Gradle **不会**顺延到下一个仓库，所以顺序必须是镜像在前。
28. Android 构建的 JDK 必须钉在 **21**：AGP 8.2.1 不支持 JDK 24/27，
    而 `brew install maven` 顺带装的 openjdk 是 27（`mvn -v` 显示 Java 27）。

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
- **验收：** ① `npm test` 全绿（含打真实网关的链路测试）② `gradlew assembleDebug` 出 APK
- **状态：** ✅ **完成**
  - ✅ `mobile/test` **14/14 全绿**（8 项纯逻辑单测 + 6 项打真实运行中网关的链路测试，含熔断降级断言）
  - ✅ `assembleDebug` **BUILD SUCCESSFUL** → `app-debug.apk` **3.6M**
    sha256 `dbea648d15097532b07a0cc2a7876210cda1769ff5b17a75ebfcba2a6e93f014`
  - ✅ `mobile/scripts/verify-apk.sh` 用 `aapt2 dump badging` **真正解析**了 APK：
    包名 `com.demo.microservice`、minSdk 22 / targetSdk 34；并确认 `assets/public/index.html`
    与 `api.js` 都在包里、index.html 确实 `import './api.js'` 且含模拟器网关地址 `10.0.2.2`
    （不是「文件存在就算成功」）
  - ⚠️ **仍未被验证的部分（如实声明）**：本机无真机、无模拟器（SDK 只装了 platform-34，
    没有 emulator 包与系统镜像），**「APK 装到设备上点击可用」未经任何验证**。
    本里程碑的 Done 判据因此定义为：
    **APK 能构建 + 产物内容正确 + App 客户端逻辑经真实网关验证通过**；
    设备端运行不在本机 Done 判据内，README 已显式声明。

---

## 明确定义「完成」（Done）

**本地**：`mvn -B -ntp clean install` 全绿 + `bash scripts/e2e.sh` 8/8 PASS 且退出码 0
+ `mobile` 测试全绿 + APK 产出并通过 `verify-apk.sh`。

**CI**：`.github/workflows/ci.yml` 的 `backend` / `mobile-unit` / `android` 三个 job 全绿
（= 上面那套命令在一台干净的 runner 上同样通过）。

**状态**：✅ 已验证 —— run `37857792070` 三个 job 全 **success**，产物
`service-jars`（305MB）+ `app-debug-apk`（3.37MB）。
CD 的 `release` job 按设计需打 tag 才触发，尚未执行（打 tag 后会把 jar 与 APK 附到 GitHub Release）。

任一条未达成即视为未完成，不允许「看着还行」。

## 停止条件

- 成功：M0–M3 全绿 → 进入 M4
- 上限：同一模块连续失败 3 次即停下来重估方案（而不是继续堆补丁）
- 人审：需要改基准版本 / 引入外部中间件 / 改验收标准时，必须先问用户
