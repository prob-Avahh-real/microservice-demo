# 微服务演示系统 (microservice-demo)

Spring Cloud 微服务最小闭环：注册发现 + 统一配置 + API 网关 + 服务间调用 + 熔断降级，
外加一个 Capacitor 打包的 Android 客户端。**零外部中间件**（不用 Docker / Nacos / MySQL / Redis），
5 个进程 + H2 内存库，一条命令全起。

## 用途与 Target 判定

后端微服务系统 + 一个 Android 客户端（用于验证网关链路）。

- 移动端：**需要**（Capacitor → APK）
- 智能硬件：**na** —— 微服务后端没有穿戴/嵌入式形态，按根 `AGENTS.md`「用途决定 Target」
  显式标注为不适合，不为「形式上的 Harness 完整」硬造手表端

## 目标平台

| 平台 | 方式 | 产出 |
|------|------|------|
| 🖥 服务端（主） | Spring Boot 4 可执行 jar | 5 个 jar |
| 🤖 Android | Capacitor 6 → Gradle | APK |

## 架构

```
Android / curl ─▶ api-gateway :8080 ─lb://─▶ order-service :8081 ─Feign─▶ inventory-service :8082
                        └────── Eureka :8761（注册发现）──────┘
                               Config Server :8888（统一配置, native）
```

| 服务 | 端口 | 职责 |
|------|------|------|
| config-server | 8888 | 统一配置中心（配置随包分发，不依赖启动目录） |
| eureka-server | 8761 | 注册与发现 |
| inventory-service | 8082 | 库存查询 / 扣减 + 故障注入开关 |
| order-service | 8081 | 下单编排 + Feign 调库存 + Resilience4j 熔断降级 |
| api-gateway | 8080 | 统一入口、路由、CORS、X-Trace-Id |

调用链：`/api/orders/**` → 去掉 `/api` → `lb://order-service` → `Feign` → `lb://inventory-service`。

## 技术栈与版本基准

| 组件 | 版本 |
|------|------|
| JDK | **21**（必须。AGP 8.2.1 不支持 JDK 24/27，而 brew 装的 maven 默认跑 27） |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 |
| Eureka 组件 | 5.0.2 |
| Gateway | 5.0.3（`spring-cloud-starter-gateway-server-webflux`） |
| Capacitor | 6.2.2 → AGP 8.2.1 / Gradle 8.2.1 / compileSdk 34 |

**Boot 与 Cloud 必须成对升级。** Boot 版本取 `spring-cloud-dependencies` BOM 里自述的
`<spring-boot.version>`，不要照抄 Initializr 页面显示的字符串
（它显示 `4.1.1.RELEASE`，该坐标在 Maven Central 是 404）。
改版本前先跑 `mvn -B clean install` 与 `bash scripts/e2e.sh`。

## 命令

```bash
# 后端
mvn -B clean install            # 编译 + 22 个测试（10 inventory / 10 order / 2 gateway）
bash scripts/run-all.sh         # 一键起全套（幂等，已在跑的跳过）
bash scripts/stop-all.sh        # 停全套（按 PID + 端口兜底）
bash scripts/e2e.sh             # 端到端验收：8 项断言，退出码非 0 即失败
bash scripts/lint-sh.sh         # shell 体检：bash -n + bash 3.2 多字节变量名坑

# Android 客户端
cd mobile && npm install        # postinstall 会自动给 Capacitor 子工程打镜像补丁
npm test                        # 14 项：8 个纯逻辑单测 + 6 个打真实网关的链路测试
npm run build:apk               # → android/app/build/outputs/apk/debug/app-debug.apk
bash mobile/scripts/verify-apk.sh   # 用 aapt2 真解析 APK + 校验包内 web 资源
```

## 验证环 / Done

**Done（硬指标，全过才算完成）**

1. `bash scripts/lint-sh.sh` 退出码 0
2. `mvn -B clean install` BUILD SUCCESS，测试全绿（当前 22）
3. `bash scripts/e2e.sh` 输出 **8/8 PASS** 且退出码 0
4. `cd mobile && npm test` 全绿（链路测试需先 `run-all.sh`）
5. `bash mobile/scripts/verify-apk.sh` 通过

**Sensor 清单（改哪块就跑哪个，别靠嘴说）**

| 改动范围 | 必跑 |
|----------|------|
| 任何 `.sh` | `scripts/lint-sh.sh` |
| Java 代码 / 配置 | `mvn -B clean install` + `scripts/e2e.sh` |
| 网关路由 / 注册中心 / 配置中心 | `scripts/e2e.sh`（第 5–8 条是这部分唯一有效的传感器） |
| `mobile/www`（客户端逻辑） | `cd mobile && npm test`（含打真实网关的链路测试） |
| Android 构建配置 | `npm run build:apk` + `scripts/verify-apk.sh` |

**停止条件**：① 成功；② 同一模块连续失败 3 次即停下重估方案（不要继续堆补丁）；
③ 要改版本基准 / 验收标准 / 引入外部中间件 → 先问用户。

## 扩展点：新增一个服务只需 3 步

1. 复制 `inventory-service/` 的 pom 结构建模块，父 pom 加 `<modules>` 项
2. 在 `config-server/src/main/resources/config-repo/<服务名>.yml` 放它专属的配置
3. 在 `api-gateway/src/main/resources/application.yml` 的
   `spring.cloud.gateway.server.webflux.routes` 里加一条（`lb://<服务名>` + `StripPrefix=1`）

**核心管线不动。** 服务间调用一律写服务名（`@FeignClient(name = "...")`），禁止硬编码 `host:port`；
`common/` 模块只放对外契约（DTO / 错误码 / 状态枚举），不放实现。
配置中心只放**可调参数**（阈值、超时、熔断参数），路由与端口属于部署拓扑，放服务本地。

## 助手工作须知（Harness / Loop 落点）

- 遵循根 `/Users/skat/AGENTS.md` 的**通用性·可复用 / 设计原则 / 人月神话 / 分阶段治理 /
  Harness Engineering / Loop Engineering** 全套永久规则；本文件只写本工程的**具体落点**，不重述规则。
- **Guide = 本文件**，**Sensor = 上面的验证环命令**。同类失误出现第二次必须沉淀为规则或测试（棘轮），
  不是再写一条「注意事项」——`scripts/lint-sh.sh` 就是这么来的。
- 开工前写清 Done；每切片过验证环；状态外置到 `PROJECT_NOTES.md`（里程碑台账 + 踩坑清单）。
- **失败必须可观测，禁止把失败涂成成功**：`DEGRADED` 不等于成功；`remaining = -1` 表示未知，
  不用 `0` 冒充已知；降级也要落库并写明 `reason`。
- 三态语义不要混：`CREATED` / `REJECTED`(业务拒绝, HTTP 200) / `DEGRADED`(技术降级, HTTP 200)；
  只有结构性非法（`quantity <= 0`）才是 HTTP 400。

## 平台踩坑速查（本机实测，完整 28 条见 PROJECT_NOTES.md）

| 现象 | 原因 | 处理 |
|------|------|------|
| 网关 503 `Unable to find instance` | Clash 的 TUN 拦掉「连本机 LAN IP」的回连（自连超时、`localhost` 正常） | 服务注册用 `localhost`，不用 `prefer-ip-address: true` |
| 服务「注册成功却查不到」 | Eureka 的 `/eureka/apps` 是 30s 读缓存快照 | demo 调到 2s；**断言要轮询**，别对最终一致的读路径拍单张快照 |
| 网关起来了但一条路由都没有 | 前缀应为 `spring.cloud.gateway.server.webflux.*`（写错不报错） | 改前缀；`GatewayRoutesTest` 会钉住 |
| Android 拉不到依赖 | `maven.google.com`/`dl.google.com` 直连被墙，且 Gradle 走 Clash 代理会 TLS 握手失败 | 用国内镜像直连；Capacitor 子工程靠 `mobile/scripts/patch-capacitor-repos.cjs` 打补丁 |
| 脚本报 `unbound variable` 且变量名乱码 | bash 3.2 会把紧跟变量的中文字符吃进变量名 | 一律写 `${var}`；`scripts/lint-sh.sh` 会抓 |
| 找不到 Android SDK | 本机在 `/opt/homebrew/share/android-commandlinetools`，**不是** `~/Library/Android/sdk` | `mobile/scripts/build-apk.sh` 已固化 |

## 文档

- `README.md` —— 架构、接口清单、验收说明、工程取舍表、9 条已知限制
- `CHANGELOG.md` —— 版本变更记录（Keep a Changelog 体例），含「未验证 / 未产出」清单
- `PROJECT_NOTES.md` —— 里程碑台账（Done 判据 / 停止条件）+ 28 条踩坑清单
- 可复用知识已沉淀为 skill `spring-cloud-microservices`（含 Capacitor 本地构建 reference）

## 已知未验证项（不要过度声明）

本机无真机、无模拟器（SDK 只装了 platform-34，没有 emulator 包与系统镜像），
**「APK 装到设备上点击可用」未经验证**。Android 侧的 Done 只到
「能构建 + 产物内容正确 + 客户端逻辑经真实运行的网关验证通过」。
