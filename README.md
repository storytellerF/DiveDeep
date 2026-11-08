这是一个面向 Android、iOS、Web 和 Desktop（JVM）的 Kotlin Multiplatform 项目。

### Android 屏幕翻译

Debug 和 Alpha 可与 Release 并存安装，application ID 分别带 `.debug` 和 `.alpha` 后缀。启动图标带 **DEBUG** 和 **ALPHA** 标记。各变体有独立设置和数据，旧的不带后缀的 Debug 安装不会自动迁移到新 Debug 包。

首页提供翻译开关、无障碍设置入口，以及明确标注的翻译显示示例。使用屏幕翻译前，在 Android 无障碍设置启用 DiveDeep；只打开首页开关不会授予无障碍访问。也可通过快捷设置磁贴切换翻译。

打开**翻译服务**，选择本地 llmd IPC 或 HTTP/HTTPS API，配置模型与连接，然后点击**保存翻译配置**。本地 llmd 需要在选定 llmd 变体中授权调用方。API 密钥始终遮蔽显示，切换后端时保留已输入连接信息。同一会话在首页与配置页之间切换时，尚未保存的编辑仍会保留。

打开**应用排除**，选择不需要翻译的应用。勾选的应用被排除，修改自动保存。翻译服务开发选项中的**使用 Mock 翻译**仅用于测试。

文本语言在设备上离线检测。只有被识别为与系统语言不同的文本，才会显示翻译图标并发送翻译。同一语言的地区变体视为相同语言；无法可靠识别语言的文本会跳过。

点击悬浮翻译图标打开翻译详情。面板先显示译文，下方显示原文。长内容在面板内滚动，**复制译文**复制结果，**关闭**返回页面。悬浮控件之外的区域仍将触摸传递给底层页面。如果本地 llmd 停止或不可用，悬浮控件仍跟随可见文本，也仍能关闭；新的翻译需要提供方恢复就绪。

* [/iosApp](./iosApp/iosApp) 包含 iOS 应用。即使通过 Compose Multiplatform 共享 UI，iOS 应用仍需此入口。项目的 SwiftUI 代码也应放在这里。

* [/shared](./shared/src) 存放 Compose Multiplatform 应用间共享代码，包含以下子目录：
  - [commonMain](./shared/src/commonMain/kotlin) 存放所有 target 通用代码。
  - 其他目录中的 Kotlin 代码只面向目录名所示平台编译。例如，Kotlin 应用的 iOS 部分调用 Apple CoreCrypto 时，应放在 [iosMain](./shared/src/iosMain/kotlin)；修改 Desktop（JVM）专用部分时，应放在 [jvmMain](./shared/src/jvmMain/kotlin)。

### 运行应用

使用 IDE 工具栏运行控件提供的配置，也可使用以下命令和选项：

- Android 应用：`./gradlew :androidApp:assembleDebug`。
- 桌面应用：
  - 热重载：`./gradlew :desktopApp:hotRun --auto`。
  - 常规运行：`./gradlew :desktopApp:run`。
- Web 应用：
  - Wasm target（更快，面向现代浏览器）：`./gradlew :webApp:wasmJsBrowserDevelopmentRun`。
  - JS target（较慢，支持旧浏览器）：`./gradlew :webApp:jsBrowserDevelopmentRun`。
- iOS 应用：在 Xcode 中打开 [/iosApp](./iosApp) 并运行。

### 运行测试

使用 IDE 编辑器行号栏的运行按钮，或执行 Gradle 任务：

- 静态分析：`./gradlew detekt`。
- Android 测试：`./gradlew :shared:testAndroidHostTest`。
- 桌面测试：`./gradlew :shared:jvmTest`。
- Web 测试：
  - Wasm target：`./gradlew :shared:wasmJsTest`。
  - JS target：`./gradlew :shared:jsTest`。
- iOS 测试：`./gradlew :shared:iosSimulatorArm64Test`。

### Android 翻译集成

应用支持 Release、Daily 和 Debug llmd 包。在本地 IPC 设置选择已安装变体，并在该 llmd 变体中授权 DiveDeep。

贡献者环境配置、确定性回归测试和真实设备翻译集成测试见 [DEVELOPMENT.md](DEVELOPMENT.md#android-端到端测试)。

---

进一步了解 [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)、[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform) 和 [Kotlin/Wasm](https://kotl.in/wasm/)……

欢迎在公开 Slack 频道 [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web) 反馈 Compose/Web 和 Kotlin/Wasm 的使用体验。遇到问题请在 [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP) 报告。
