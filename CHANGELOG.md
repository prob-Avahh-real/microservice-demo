# Changelog

本文件记录显著变更。格式参考 Keep a Changelog，版本号遵循语义化版本。

## [1.0.0] - 2026-10-08

首个可用版本：Spring Cloud 微服务最小闭环 + Android 客户端。所有指标均为实测结果，未达成的项目列在末尾「未验证 / 未产出」。

### Added — 后端微服务

- `common` —— 跨服务对外契约：`ApiResponse` / `ErrorCodes` / `OrderStatus` / `InventoryView` / `DeductCommand` / `DeductResult`（只放契约，不放实现）
- `config-server` (:8888) —— 统一配置中心，native profile，配置随包分发（不依赖启动目录）
- `eureka-server` (:8761) —— 注册与发现
- `inventory-service` (:8082) —— 库存查询 / 扣减（JPA 乐观锁 `@Version`）、lowStock 阈值、单次扣减上限、故障注入开关
- `order-service` (:8081) —— 下单编排；Feign 调库存；Resilience4j 熔断降级；单笔上限与熔断参数均由配置中心下发并通过 Customizer 真正接线
- `api-gateway` (:8080) —— 响应式 Spring Cloud Gateway；配置驱动路由（`lb://`，无硬编码地址）；全局 CORS；`X-Trace-Id` 全局过滤器 + 访问日志
- 三态订单结果契约：`CREATED` / `REJECTED`（业务拒绝，HTTP 200）/ `DEGRADED`（技术降级，HTTP 200）；结构性非法才是 HTTP 400
- 配置自证接口 `GET /diagnostics/config` —— 返回生效值 + 属性源里是否含 `configserver`
- 熔断降级也落库并写明 `reason` + 上游异常类型；`remaining = -1` 表示未知，不用 `0` 冒充已知

### Added — Android 客户端

- Capacitor 6.2.2 工程（`mobile/`），网关地址可配置，默认值按运行环境自动选（模拟器 `10.0.2.2` / 本机 `localhost`）
- 界面：连接测试、库存查询、下单、故障注入开关、请求日志；三态结果用不同颜色区分
- `mobile/www/api.js` —— 纯逻辑 API 模块（fetch 可注入），WebView 与 Node 测试共用同一份
- `mobile/scripts/build-apk.sh` —— 一条命令出 APK（SDK 路径 / JDK / 仓库全部固化）
- `mobile/scripts/verify-apk.sh` —— 用 `aapt2 dump badging` 真解析 APK，并校验包内 web 资源与入口引用
- `mobile/scripts/patch-capacitor-repos.cjs` —— 幂等给 Capacitor 自带子工程插入国内镜像（`cap sync` 会重生成，故每次构建前重打）

### Added — 工程化与自动化

- `scripts/run-all.sh` —— 一键起 5 个进程：幂等、限制堆内存（8GB 机器）、健康等待、按**真实业务请求**判定路由打通（不只看端口 LISTEN）
- `scripts/stop-all.sh` —— 按 PID 停 + 端口兜底，防止孤儿进程占端口
- `scripts/e2e.sh` —— 端到端验收：8 项断言，退出码即结论
- `scripts/lint-sh.sh` —— shell Sensor：`bash -n` + 抓「`$var` 紧跟非 ASCII 字符」的 bash 3.2 变量名坑
- `mobile/test/api.live.test.js` —— 用 App 自己的 API 模块打**真实运行中的**网关，验证「客户端逻辑 ↔ 网关 ↔ 后端服务」确实接通

### Added — CI/CD

- `.github/workflows/ci.yml` —— 4 个 job：`backend`（OS 矩阵：lint → 构建单测 → 端到端）/
  `mobile-unit`（与 backend **并行**，快速失败）/ `android`（job 内构建 jar → 起服务 → 链路测试 → 出 APK → 校验产物）/
  `release`（打 tag 时构建 jar 并把 jar 与 APK 附到 GitHub Release）
- `backend` 为 **OS 矩阵**（ubuntu / macOS / Windows）：编译 + 22 项单测三平台都验；
  端到端只在 Linux / macOS 验（依赖 POSIX 进程管理），Windows 那一格显式只承诺「编译 + 单测」
- **产物瘦身**：取消每次 CI 上传 `service-jars`（5 个 Boot fat jar 合计 ~327MB），
  改为 android / release job 内自行 `mvn -DskipTests package`（Maven cache 复用，几十秒）；
  日常 CI 产物只剩 APK(3.4MB) + 失败日志，可下载的 jar 改由 Release 提供
- **concurrency 保留，但不再掐主分支**：`cancel-in-progress` 改为条件式（仅 PR 取消），
  主分支上的新推送改为**排队**而不是取消上一次。
  起因：推一个纯文档提交，把正在跑的完整验证 run 取消了，结果还得重跑。
- **CI 预推送守卫**（本地预防那条腿）：`.githooks/pre-push` → `scripts/ci-guard.sh`，
  CI 在跑时直接拦住 push 并列出在跑的 run；**失败开放**（gh 缺失/未登录/断网/超时只提示不拦，
  一个会因工具故障挡住正常推送的守卫比没有更糟）；逃生口 `SKIP_CI_GUARD=1`。
  启用：`bash scripts/setup-git-hooks.sh`（`core.hooksPath` 是本地配置，不随克隆自动生效）
- CI 跑的就是本地那套命令，本地绿 = CI 绿，避免「我机器上能跑」
- 失败时上传 `.run/logs/` 与 `.run/e2e.log`（排查用）；日常成功产物只有 APK
- `scripts/stop-all.sh` 增加 lsof 缺失时的兜底（Linux runner 通常没装 lsof，原来会直接失效）
- `mobile/package.json` 拆出 `test:unit` / `test:live`，让纯逻辑单测无需后端即可并行跑
- 工作流经 `actionlint` 校验（0 问题）；已在 GitHub Actions 上**实际跑通**：
  `backend` / `mobile-unit` / `android` 三个 job 全 success，产物 `service-jars`(305MB) 与 `app-debug-apk`(3.37MB)

### Fixed（CI 首次真实运行暴露的两个「本机假设」）

- **`gradle-wrapper.jar` 从未进过仓库**：`.gitignore` 里的 `*.jar` 误伤了 Gradle wrapper 的引导 jar。
  本地因文件就在磁盘上所以一直能构建，CI 从仓库检出必挂：
  `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`
  → 放行 `!**/gradle/wrapper/gradle-wrapper.jar`
- **受控文件里写死了本机 JDK 路径**：`mobile/android/gradle.properties` 的
  `org.gradle.java.home=/opt/homebrew/...` 让 CI 报 `Java home supplied is invalid`
  → 移除该行，改由 `build-apk.sh` 注入 `JAVA_HOME`（本机与 CI 同一套逻辑）
- **「本机干净检出」验证有盲区**：同一台机器上那些路径依然存在，所以这类问题只有换机器才暴露
  → `scripts/lint-sh.sh` 增加第 3 项检查：受版本控制的配置类文件（properties / gradle / xml / yml / json …）
  不得出现 `/opt/homebrew` 或 `/Users/` 绝对路径（并做过反向测试，确认它会报警）

### Fixed（CI 矩阵首次运行暴露的第三个「跨平台假设」）

- **Windows 上 Python 输出编码炸掉**：`lint-sh.sh` 里的 Python 要打印中文与 `✔`，
  而 Windows 的 Python 默认输出编码是 **cp1252** → 直接抛 `UnicodeEncodeError`（不是输出难看，是报错退出）。
  已修：脚本内 `export PYTHONIOENCODING=utf-8` + `PYTHONUTF8=1`；同时把写死的 `python3`
  改成可发现（`command -v python3 || command -v python`，Git Bash 里通常只有 `python`）。
  → **这个 OS 矩阵不是形式主义**：它第一次运行就抓到了这个只在 Windows 现形的真缺陷
  （同一提交上 ubuntu 与 macOS 都是绿的，含完整 8 项端到端）。
- **`android` job 不再 `needs: backend`**：它自带一切（自行构建 jar + 起服务自测），
  却因为矩阵里 Windows 那一格失败而被 `skipped`，把 APK 产出一起拖住了。
  改为独立 job，让它只反映自身结果；「全平台都绿才允许发布」的门禁放在 `release` 的 `needs` 上。

### Fixed（执行 CD 时暴露的第四个缺陷）

- **推 tag 不触发流水线**：`on.push` 只写了 `branches: [main, master]`，而 tag 推送不匹配 `branches`，
  于是 `release` job 的 `if: startsWith(github.ref, 'refs/tags/')` **永远为假** —— CD 等于没接上。
  已修：显式加 `tags: ['v*']`。
  → 这类问题只有**真的执行一次 CD**（打一次 tag）才会现形，配置写完不等于接通。
- **CD 已验证**：`v1.0.0` 的 tag 运行全 success 并发布了 Release，
  附 5 个服务 jar + `common` + `app-debug.apk`（共 7 个资产）；
  APK 已从 Release 下载回来用 `aapt2 dump badging` 独立复核（包名 `com.demo.microservice`，web 资源齐全）。

### Tests

- 后端 **22 项通过**：inventory 10（6 纯逻辑单测 + 4 HTTP 集成）、order 10（6 + 4）、gateway 2（路由加载断言）
  - 覆盖三条下单路径：成功 / 库存不足（业务拒绝）/ 上游失败（降级），并断言业务拒绝不落进 fallback
  - gateway 的 2 项专门钉住「配置前缀写错会静默失效」这个点
- 客户端 **14 项通过**：8 个纯逻辑单测 + 6 个打真实网关的链路测试（含 `CallNotPermittedException` 断言，证明熔断器真的跳闸）
- 端到端 **8/8 PASS**（冷启动实跑，可重复执行）

### Fixed（开发过程中定位并修掉的真实缺陷）

- **网关连不上后端**：服务用 `prefer-ip-address: true` 注册成本机 LAN IP，而 Clash 的 TUN 接口会拦掉「连本机 LAN IP」的回连（自连超时，`localhost` 正常）→ 改为注册 `localhost`
- **Eureka 注册表断言随机假失败**：`/eureka/apps` 是默认 30s 的读缓存快照，服务注册成功（日志 204）也可能暂时查不到 → 服务端调为 2s，且断言改为轮询（注册表收敛本就是最终一致）
- **`run-all.sh` 假成功**：`(( fail == 0 )) && wait_xxx` 丢弃了返回值，路由不通时仍打印「全套已就绪」并 `exit 0` → 改为显式 `if ... then wait_xxx || fail=1; fi`
- **`order-service/application.yml` 被整文件覆盖**，启动报 `No spring.config.import property has been defined` → 恢复完整配置（根因：本该用局部替换却用了整文件写入）
- **e2e 第 8 条读数错误**：正则抓 XML 的 `<name>` 会把 `dataCenterInfo` 里的 `<name>MyOwn</name>`（"my own datacenter"）当成第 4 个应用 → 改走 JSON 只取 `applications.application[].name`，并统一大小写比较
- **Android 依赖解析失败**：`maven.google.com` / `dl.google.com` 直连被墙，而 Gradle 自己的 HTTP 客户端走 Clash 代理会 TLS 握手失败（`Remote host terminated the handshake`，同一代理下 curl 与 JDK HttpClient 均正常）→ 改用直连可达的国内镜像，并为 Capacitor 自带子工程打补丁（超时是「失败」不是「找不到」，所以镜像必须排在 `google()` 之前）
- **单测数据撞上前置校验**：用 `quantity=99` 验「库存不足」，但单次扣减上限 50 会先判成 `INVALID_REQUEST` → 改用 4 件，让拦截它的只可能是库存逻辑
- **App 链路测试断言错误**：把「超出业务上限」当成 HTTP 400，实际按契约为 200 + `REJECTED` → 修正断言，并补一条「结构性非法才 400」的对照用例
- **bash 3.2 变量名被多字节字符吃掉**：`"...= $before（中文..."` 中的全角括号被解析进变量名 → `unbound variable`。全项目统一写 `${var}`，并做成 `lint-sh.sh` 自动检查（同一失误第二次即沉淀为 Sensor）

### Docs

- `README.md` —— 架构、接口清单、三态与 HTTP 状态码约定、验收 8 项说明、工程取舍表、9 条已知限制
- `AGENTS.md` —— 用途与 Target 判定（智能硬件显式标 `na`）、版本基准与「不可照抄 Initializr 版本号」的原因、验证环 / Done、Sensor 清单表、扩展点（新增服务 3 步）、平台踩坑速查
- `PROJECT_NOTES.md` —— 里程碑台账（M0–M4 及 Done 判据 / 停止条件）+ 28 条踩坑清单
- 可复用知识沉淀为 skill `spring-cloud-microservices`（含 Capacitor 本地构建 reference）

### Known limitations

- **「APK 装到设备上点击可用」未经验证** —— 本机无真机、无模拟器（Android SDK 只装了 platform-34，无 emulator 包与系统镜像）
- 无分布式事务：`order-service` 落库失败不会回滚 `inventory-service` 已扣减的库存（需 TCC / Saga / 本地消息表）
- 配置不支持热更新（native profile + classpath 配置源，改配置需重新打包）
- 无鉴权；网关 CORS 对所有来源开放
- 观测只有 actuator + 日志，未接 Prometheus / 链路追踪后端
- Eureka 关闭自我保护、剔除间隔 3s、响应缓存 2s —— 均为单机 demo 取舍，生产必须恢复默认
- 仅单实例部署，未验证多实例负载均衡

### Not produced（Phase 9 清单中尚未产出的项）

- 代码审查 / 安全审计 / 依赖审计报告（`code-review-reports/`）
- `git tag: engineering-v2-<date>`（CD 的 Release 需要打 tag 触发，本版尚未打）

## [Unreleased]

- 分布式事务（Saga / 本地消息表）
- Prometheus 指标 + 链路追踪（Micrometer Tracing + Zipkin）
- JWT 鉴权与网关鉴权过滤器
- CI/CD 流水线 + 自动化测试门禁
- 设备端（真机或模拟器）运行验证
- 多实例部署与负载均衡验证
