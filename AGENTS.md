# AGENTS.md

本文件约束在本仓库中工作的自动化编码代理。除非任务另有明确说明，规则适用于整个仓库。

## 项目基线

- 这是单模块 Android 打卡机应用，应用模块为 `app`，包名/命名空间为 `com.punch.app`。
- 主代码使用 Java 8 和 AndroidX；当前 `compileSdk`/`targetSdk` 为 34，`minSdk` 为 24。
- 构建类型包括 `debug`、`release` 和用于黑盒测试的 `smoke`。`release` 启用代码压缩和资源收缩，但仓库未配置发布签名。
- 版本、依赖、SDK 和构建配置以当前源码及 Gradle 文件为准。README 和历史设计文档可能滞后，不能覆盖实际代码。
- 不要为了完成普通任务引入 Kotlin、替换构建系统、升级依赖或进行无关重构。

## 目录与职责

- `app/src/main/java/com/punch/app/`：生产代码。
- `activity/`、`fragment/`、`adapter/`、`widget/`：界面与交互。
- `network/`：HTTP 客户端、接口封装、响应模型和交互日志。新增接口应继续通过 `ApiClient`、`ApiService`、`ApiEndpoints` 及对应 DTO 实现，不要在页面中直接拼装 HTTP 请求。
- `db/DatabaseHelper.java`：SQLite 表、迁移、本地记录和同步队列；当前数据库版本由 `Constants.DB_VERSION` 定义。
- `service/`：心跳、员工同步、打卡同步、重试策略和同步触发来源。
- `face/`：百度 Face SDK 初始化、注册、识别和底库操作。
- `utils/`：会话、生命周期门禁、更新时间、网络和设备策略等公共能力。
- `app/src/test/`：JVM 单元测试；`app/src/androidTest/`：设备测试；`app/src/debug/` 和 `app/src/smoke/`：测试宿主及测试运行时实现。
- `scripts/`：本地回归和 ADB UI 冒烟脚本。
- `doc/`：设计与实施记录，只提供上下文，不自动成为新的产品要求。

## 修改规则

### 工作区与文件

- 开始前检查 `git status --short`。仓库可能已有用户修改；不得覆盖、回滚、格式化或删除与当前任务无关的变更。
- 只修改任务需要的最小文件集合。不要提交生成目录、IDE 状态、`local.properties`、APK/AAB 或临时截图。
- 文本使用 UTF-8；Java、Gradle、XML、JSON、Markdown 等使用 LF；PowerShell、BAT、CMD 使用 CRLF。遵守 `.editorconfig` 和 `.gitattributes`，禁止对全仓库做未经验证的转码或换行重写。
- `app/libs/` 中的 AAR/JAR/SO、模型文件、图片及其他二进制资源不得当作文本编辑，也不要在非相关任务中替换。
- 不要直接修改 `app/release/output-metadata.json` 或其他构建产物；让 Gradle 重新生成。

### 数据、网络与安全

- 数据库表或列变化必须同步提高 `Constants.DB_VERSION`、实现向前迁移，并补充迁移/回归测试。不能依赖清空应用数据完成升级。
- `face_sdk_ids` 保存员工 ID 到 Face SDK 数字 ID 的稳定映射。删除员工数据时不得复用旧映射；修改映射算法必须验证升级兼容性与冲突处理。
- 保持网络调用经过既有认证、错误解析和交互日志边界。不得在日志、测试夹具或错误信息中泄露 token、密码、完整人脸数据及其他敏感数据。
- 修改 API 字段、路径或响应解析时，同时检查相应 DTO 和 `app/src/test/java/com/punch/app/network/` 下的契约测试。
- HTTP 基地址、公司 ID、设备 ID 等配置继续通过现有配置和会话组件读取，不要在业务代码中硬编码环境值。

### 同步与重试

- `SyncCoordinator` 的心跳/员工同步、打卡同步和快照清理使用不同的串行执行器。不要把耗时打卡上传放回心跳执行器，也不要无界并发上传。
- 同步入口必须携带明确的 `SyncTrigger`，并保持自动触发与人工触发的语义差异。重试次数的每日重置规则应由统一策略控制，不能分散到 Activity、Fragment 或广播回调中。
- 修改重试上限、日期重置、队列选择或批次预算时，必须覆盖 `PunchSyncPolicyTest`、`SyncTriggerTest` 以及数据库队列边界。
- 保持离线打卡可落库、上传可重试、重复调用可安全处理。不要通过删除失败队列项来掩盖重试问题。

### 页面生命周期与统计请求

- `PunchFragment` 和 `RecordsFragment` 的异步结果必须同时校验当前 View 代次和页面有效性。`onDestroyView()` 中取消任务/回调、释放相机及 View 引用，旧页面结果不得更新重建后的 View。
- `/v3/handheld/clock/statistics` 的自动请求只允许通过 `StatisticsRequestCoordinator.Reason` 白名单触发：`PANEL_READY`、`PANEL_ENTER`、`FILTER_CHANGED`、`NEXT_PAGE`、`USER_REFRESH`。
- `onResume()`、Spinner 初始化回调、View 重建引发的重复生命周期回调不得直接请求统计接口。Spinner 只有在用户实际改变查询条件后才能使用 `FILTER_CHANGED`。
- 新增统计请求来源前先扩展协调器与单元测试，明确去重、进行中请求及旧结果失效语义，禁止从任意生命周期回调直接调用 API。
- 其他打卡机上传的数据不会自动推送到本机；需要更新时应由上述白名单中的用户行为或产品明确增加的刷新机制触发。

### Face SDK 与设备能力

- Face SDK 初始化、底库注册/删除和识别资源具有全局状态及线程约束。继续通过现有管理类串行化底库操作，避免页面绕过管理层直接调用 SDK。
- 相机、Face SDK、广播、定时任务和 Executor 的获取与释放必须成对，且释放路径应覆盖页面销毁、Activity 退出和异常返回。
- Kiosk、Device Owner、静默安装和 ADB 操作会改变真实设备状态。除非用户明确授权设备操作，否则只修改/验证代码，不执行设置所有者、清数据、卸载或安装 APK 等命令。

## 验证

从仓库根目录使用 PowerShell 执行与变更风险匹配的最小验证：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat lintDebug
```

- 生产代码的默认完整本地验证为：`.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug`。
- `release` 构建成功只说明产生了构建产物，不代表 APK 已使用发布证书签名。
- 当前 `lintDebug` 存在已知基线问题：`app/src/debug/AndroidManifest.xml` 声明了 debug source set 中缺失的 `UiTestSetupWizardHostActivity`。不得用禁用规则或生成 Lint baseline 隐藏它；如果任务没有修复此问题，应在交付说明中如实区分既有失败与新增失败。
- 网络改动至少运行对应 API 单元测试；数据库/同步改动至少运行注册表、重试策略和触发器测试；页面生命周期或统计请求改动至少运行 `LifecycleRequestGateTest` 和 `StatisticsRequestCoordinatorTest`。
- 有在线 ADB 设备且任务明确允许设备操作时，可执行 `.\scripts\run-ui-smoke.ps1`；完整回归入口为 `.\scripts\run-regression.ps1`。这些脚本会构建/安装应用、启动 Activity、强停应用并操作设备，不得在未授权设备上运行。
- 文档或配置修改至少执行适用的静态检查和 `git diff --check`。不要声称未实际运行的测试已经通过。

## 交付要求

- 交付前重新查看 `git diff` 和 `git status --short`，确认只包含当前任务的预期修改，并保留用户原有变更。
- 简要说明修改了什么、运行了哪些验证及其结果、哪些验证未运行，以及任何已知的既有失败。
- 不提交、推送、创建发布包或操作真实设备，除非用户明确要求。
