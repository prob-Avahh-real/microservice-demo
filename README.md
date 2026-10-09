# microservice-demo

[![CI](https://github.com/prob-Avahh-real/microservice-demo/actions/workflows/ci.yml/badge.svg)](https://github.com/prob-Avahh-real/microservice-demo/actions/workflows/ci.yml)

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
# 0. shell 脚本体检（抓 bash 3.2 的多字节变量名坑 + 语法错误）
bash scripts/lint-sh.sh

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

**HTTP 状态码约定**（别把两种「拒绝」混在一起）：

- `quantity <= 0` 这类**结构性非法**（`@Min(1)` 校验）→ **HTTP 400** + `code=INVALID_REQUEST`
- 超出**业务上限**（单笔 > 20，值来自配置中心）→ **HTTP 200** + `status=REJECTED`
- 结论：400 表示「请求本身不合法」，200 表示「请求合法但业务上不允许」——后者是要写入台账的结果，不是错误

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
| Eureka 关自我保护 + 3s 剔除 + 2s 响应缓存刷新 | demo 要「注册/下线立刻可见」，否则排查时容易被 30s 读缓存误导 | 只适合单机 demo，生产必须恢复默认 |
| 每个 JVM `-Xmx320m` | 这台机器 8GB，5 个默认堆会把机器压垮 | 只适合 demo 负载 |

---

## 九、已知限制

- Eureka 关闭了自我保护（`enable-self-preservation: false`），这是 demo 选择，生产要打开
- 库存扣减没有分布式事务保证：`order-service` 落单失败时不会回滚 `inventory-service` 的扣减
  （需要 TCC/Saga/本地消息表，属于后续里程碑）
- 配置不支持热更新（见上表）
- 没有鉴权，网关对所有来源开放（CORS 允许所有 origin）
- 观测只有 actuator + 日志，没有接入 Prometheus / 链路追踪后端
- **Android APK 只验证到「能构建出来 + App 的 API 模块能打通真实链路」**：
  本机没有连接真机、也没有装模拟器（SDK 里没有 emulator 包与系统镜像），
  所以「APK 装到设备上点击能用」这一步**未经验证**，需要真机侧载确认

---

## 十一、CI / CD

`.github/workflows/ci.yml`（GitHub Actions）。**CI 跑的就是本地那套命令**，避免「我机器上能跑」。

| Job | 内容 | 依赖 |
|-----|------|------|
| `backend`（**OS 矩阵 ×3**） | `lint-sh.sh` → `mvn -B -ntp clean install`（22 测试）→ `e2e.sh`（起 5 进程 / 8 断言） | — |
| `mobile-unit` | `npm ci` → `npm run test:unit`（8 项纯逻辑单测） | —（与 backend **并行**，快速失败） |
| `android` | job 内构建 jar → `run-all.sh` → `npm test`（14 项，含打真网关的链路测试）→ `build:apk` → `verify-apk.sh` → 上传 APK | `backend` |
| `release` | 打 tag 时构建 jar，并把 jar + APK 附到 GitHub Release | `backend` + `android` |

**OS 矩阵**：`ubuntu-latest` / `macos-latest` / `windows-latest` 三个平台都跑「编译 + 22 项单测」；
端到端（起 5 个进程 + 8 项断言）只在 Linux / macOS 上跑 —— 它依赖 POSIX 进程管理
（`nohup` + 后台 PID + `lsof`/端口探活），Git Bash 下不可靠。
**Windows 那一格只承诺「编译 + 单测通过」**，不写成「跳过但看起来成功」。
（Android job 固定在 ubuntu：只有 ubuntu runner 预装 Android SDK。）

这个矩阵不是形式主义：它**第一次运行就抓到一个只在 Windows 现形的真缺陷** ——
`lint-sh.sh` 里的 Python 打印中文/`✔` 时，Windows 的 Python 默认输出编码是 cp1252，
直接抛 `UnicodeEncodeError`（同一提交上 ubuntu 与 macOS 全绿，含完整 8 项端到端）。
已修：脚本内强制 `PYTHONIOENCODING=utf-8`，并把写死的 `python3` 改成可发现。

`android` job **不依赖** backend 矩阵：它自行构建 jar + 自跑服务，结果独立，
免得 Windows 上与本 job 无关的问题把 APK 产出一起 skip 掉；
「全平台绿才发布」的门禁放在 `release` 的 `needs` 上。

**产物瘦身**：不再每次 CI 上传 `service-jars`。5 个 Spring Boot fat jar 合计 **~327MB**
（单个 43–85MB），跨 job 传它只为让 android job 起服务，不划算；
改为 job 内 `mvn -DskipTests package`（Maven 依赖由 `setup-java` 的 cache 复用，只几十秒）。
日常 CI 产物因此只剩 **APK（3.4MB）** + 失败日志；需要可下载的 jar 时走 **Release**。

本地等价的完整门禁：

```bash
bash scripts/lint-sh.sh
mvn -B -ntp clean install
bash scripts/e2e.sh
cd mobile && npm ci && npm test && npm run build:apk && bash scripts/verify-apk.sh
```

**实测结果**：

- 分支运行 [#37867955824](https://github.com/prob-Avahh-real/microservice-demo/actions/runs/37867955824)：
  **5 个 job 全 success**（矩阵 ×3 + 客户端单测 + Android/APK），产物只剩 `app-debug-apk`（3.37MB）
- tag 运行 [#37868410239](https://github.com/prob-Avahh-real/microservice-demo/actions/runs/37868410239)：
  全 success，并发布了 [Release v1.0.0](https://github.com/prob-Avahh-real/microservice-demo/releases/tag/v1.0.0)
  —— **7 个资产**（5 个服务 jar + `common` + `app-debug.apk`），其中 APK 已下载回来用 `aapt2` 独立复核

> CI 首轮曾暴露**两个「本机假设」缺陷**（`gradle-wrapper.jar` 从未进仓库；受控配置里写死本机 JDK 路径），
> 详见 CHANGELOG 的 Fixed 小节。两者都只有换机器才会现形 —— 本机「干净检出」验证抓不到，
> 现在由 `scripts/lint-sh.sh` 的第 3 项检查守着。

**如何触发 CD**：打 tag 即触发 `release` job，把 5 个 jar 与 APK 附到 GitHub Release。

```bash
git tag v1.0.0 && git push origin v1.0.0
```

**设计取舍**

- **不引 Docker / Testcontainers**：本工程零外部中间件（H2 内存库），runner 上直接起 5 个 JVM
  更贴近真实运行形态，也更快；为「看起来先进」引入容器只会增加偶然复杂性。
- **移动端单测与后端并行**：客户端逻辑的正确性不该等后端跑完 2–3 分钟才知道。
- **失败时上传 `.run/logs/` 与 `.run/e2e.log`**：CI 挂了要能看出是哪个服务没起来，而不是只给个红叉。
- **CD 只做产物发布**（jar + APK 附 Release）：本工程没有可推的部署环境，
  不假装有 CD 环境（区分「验证过的」和「声称的」）。
- CI 上的仓库走**官方源**（GitHub runner 能直连 `google()`/Central），
  本机走国内镜像 —— 仓库列表是「镜像优先 + 官方兜底」，两种环境都能过。

**本机推送说明**：这台机器 `github.com` 直连不通（实测 HTTP 000 / `Empty reply from server`），
git 走 Clash 代理即可（实测 200）：

```bash
git -c http.proxy=http://127.0.0.1:7897 push
```

已为本仓库设置了 `git config --local http.proxy`，所以直接 `git push` 就行。
换机器或 Clash 没开时要去掉：`git config --local --unset http.proxy`。

---

## 十二、Android 客户端

`mobile/` 是 Capacitor 工程，`mobile/www/` 里的界面经网关调用后端。

```bash
cd mobile
npm install
npm test                     # 8 个纯逻辑单测 + 6 个打真实网关的链路测试
npm run build:apk            # 产出 android/app/build/outputs/apk/debug/app-debug.apk
```

`npm test` 里的链路测试用的是**App 自己的 API 模块**（`mobile/www/api.js`），
直接打真实运行的网关 —— 所以它能证明「客户端逻辑 ↔ 网关 ↔ 订单 ↔ 库存」是通的，
而不仅仅是「APK 能编译」。跑之前需要先 `bash scripts/run-all.sh`；没起服务时测试会明确报错，不会假装通过。

**网关地址**在 App 内可改（默认值按运行环境自动选）：

| 运行环境 | 地址 |
|----------|------|
| Android 模拟器 | `http://10.0.2.2:8080`（模拟器里 `10.0.2.2` 才是宿主机的 localhost） |
| 真机（同一局域网） | `http://<Mac 的局域网 IP>:8080` |

> 注意：本机因为 Clash 的 TUN 接口，服务注册用 `localhost`（见 PROJECT_NOTES 踩坑 11），
> 但网关本身监听 `0.0.0.0:8080`，所以真机仍然可以通过局域网 IP 访问网关。
> 真机能不能连上取决于 macOS 防火墙与 Clash 的分流规则，未在本机验证。

构建环境（固化在 `mobile/scripts/build-apk.sh`；受版本控制的配置里**不含任何本机绝对路径**）：

- JDK **21**：AGP 8.2.1 不支持 JDK 24/27。由 `build-apk.sh` 显式 `export JAVA_HOME` 注入，
  **不写进** `gradle.properties` —— 那是受控文件，写死本机路径会让 CI 和别人的机器构建失败
- Android SDK：`${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}`
  （本机在 Homebrew 的 commandlinetools，**不是** `~/Library/Android/sdk`；CI 上用 runner 自带的）
- 依赖仓库：**国内镜像优先（腾讯/阿里）+ 官方源兜底，不用代理**
  （`maven.google.com` / `dl.google.com` 直连被墙，而 Gradle 走 Clash 代理会 TLS 握手失败）
- Capacitor 6.2.2 → AGP 8.2.1 / Gradle 8.2.1 / compileSdk 34

> **构建可复现性（实测）**：同一台机器上，「干净检出」（`git archive` 解出）构建出的 APK
> 与工作区构建的 **sha256 完全一致**。跨机器（macOS → Linux CI）后：
> APK 内 30515 字节的条目清单**逐条 CRC 完全相同**，唯一差异是 `META-INF/CERT.RSA` ——
> debug 签名证书来自各机器自己的 `~/.android/debug.keystore`，属签名身份差异，不是构建差异。
