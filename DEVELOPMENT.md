# 开发

## Android SDK

构建前安装 Android SDK Platform 37.1（`platforms;android-37.1`）并接受 SDK 许可证。Android 应用、共享库和测试夹具使用 API 37、minor API level 1，因为更新的 Compose Android 依赖需要此版本。主要和次要 compile SDK 版本配置于 `gradle/libs.versions.toml`，target 与 minimum SDK 版本独立设置。

构建使用 AGP 9.4.1、Gradle 9.8.2 和 compile SDK 37.1。保持这些版本兼容，不要压制不受支持 compile SDK 的警告。

## 验证

在仓库根目录运行 Android/Kotlin 基线：

```sh
./gradlew detekt :shared:jvmTest :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidTestFixture:assembleDebug --no-daemon
```

修改 Android 构建工具或设置字段后，还需构建 Alpha、Release 和 Daily，并查看具体弃用警告：

```sh
./gradlew :androidApp:assembleAlpha :androidApp:assembleRelease :androidApp:assembleDaily --warning-mode all --no-daemon
```

没有签名凭据时，Release 和 Daily 生成未签名 APK。Alpha 继承 release 设置，缺少 release 凭据时使用 debug 签名密钥。验证设置 UI 时，选择 HTTP 后端、编辑 API 密钥、保存，再重新打开设置确认持久化。输入和保存后密钥都必须完全遮蔽；切换到 llmd 会禁用该字段，但不清空内容。

通过 `ANDROID_SERIAL` 选择已解锁的 Android 设备后，聚焦的 Compose instrumentation 测试覆盖遮蔽输入、保存所输入密钥、禁用字段保留，以及加载新配置时替换：

```sh
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.divedeep.SettingsApiKeyTest --no-daemon
```

共享设备运行此命令前先获取共享设备锁。这些测试渲染隔离的设置页面，不调用 llmd IPC。

`AndroidSecureSettingsTest` 覆盖原生设置读取失败、保留其他无障碍服务，以及读取回验失败时拒绝确认删除。还会在设备上直接查询提供方而不写入 secure settings；失败场景使用注入 cursor 和记录命令的 sink。

### Android 端到端测试

Debug 安装为 `com.storyteller_f.divedeep.debug`，Alpha 为 `com.storyteller_f.divedeep.alpha`。EasyLauncher 为其启动图标和圆形／自适应图标添加红色 **DEBUG** 和紫色 **ALPHA** 标记。Release 保留基础 application ID 和原图标；已有 Daily 变体仍为 `.daily`。Kotlin 命名空间与组件类名仍为 `com.storyteller_f.divedeep`。

宿主运行器面向带后缀的 Debug 应用，保留并存 Release／旧版和 Daily 无障碍服务。恢复日志记录 application ID；缺少该字段的旧日志仍恢复原不带后缀的包。Android 测试资源选择器使用目标应用真实包名，不假定它等于 Kotlin 命名空间。

Windows/Linux 宿主运行器 `scripts/android_e2e.py` 管理完整运行：APK 准备、私有运行前快照、命令树、取消、恢复和报告。Bash 入口 `scripts/android-e2e.sh` 只转发参数。Windows 应直接使用 Python，通过 Ctrl+C 或 Ctrl+Break 取消；Git Bash 对原生 Python 执行 `kill -TERM` 属于强制终止，无法运行 Python 清理。

Kotlin/JUnit + UIAutomator 管理各用例基线、LLMD 授权和跨应用断言，不需要 Appium 服务器或会话，也不需要 npm 依赖。原 npm 授权和切换辅助程序由原生 JUnit 测试替代。设置控件测试覆盖明确启用和禁用转换，以及各 Release/Daily/Debug 目标的 Local IPC 后端保存。真实 LLMD 用例通过 `RealLlmdIntegration` 排除在普通 instrumentation 发现之外。

安装或执行任何设备测试前，先获取环境的共享设备租约。整个运行及恢复都需处于租约内。锁辅助程序属于环境，不在此内置或重新实现。宿主工作默认 1200 秒，另有 120 秒清理预算，每次命令树关闭检查最多 10 秒；租约应超过该界限，清理未经确认时不得释放或复用设备。

无需模型的检查：

```sh
python -m unittest discover -s test/e2e/unit -p 'test_*.py' -v
ANDROID_SERIAL="$DEVICE" ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon
```

宿主测试使用有状态设备边界和真实原生子进程，不声称覆盖真实设备或模型。断言未知读取不允许写入、其他服务保持完整、前提失败停止回滚、恢复不完整时保留备份，以及恢复前停止子进程和孙进程，包括创建新会话的后代。新进程测试在认领、测试执行、回滚和释放期间强制退出，再由新宿主发现并继续恢复，也在副作用前后注入故障。CI 在 Windows 和 Ubuntu 运行这些检查。

真实套件需要 Python 3.10+、JDK 和 Android SDK。解锁已租用设备。安装带 `com.storytellerf.llmd.action.BIND_IPC` 的 LLMD，导入 `gemma-4-E2B-it.litertlm` 并选中，开启 LLMD 服务并等待 Ready。仅有磁盘模型文件不代表就绪。

原生用例默认请求 `gemma-4-E2B-it`。测试其他已加载模型时，将 Gradle 项目属性 `android.testInstrumentationRunnerArguments.llmdModel` 设置为精确 LLMD 模型 ID。使用宿主运行器时，通过标准环境变量 `ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.llmdModel` 传入，使宿主继续管理准备和恢复。请求 ID 必须匹配运行中的模型，而不仅是已导入文件。

在租约内运行：

```sh
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant debug
# Optional provider installation/source-build inputs:
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant daily --llmd-apk /path/to/llmd-daily.apk
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant debug --llmd-repo ../llmd
```

也接受 `DEVICE`、`ADB`、`LLMD_VARIANT`、`LLMD_APK`、`LLMD_REPO` 和 `E2E_REPORT_DIR` 作为环境默认值。Gradle 通过 `ANDROID_SERIAL` 接收同一设备。LLMD 默认变体为 Release。可选源码构建只支持 Debug，在 LLMD 的 `app` 目录使用 `npm run tauri android build -- --debug --apk`；只有此可选提供方构建需要 Node/npm。不要直接从 LLMD 生成的 Android 目录调用 Gradle。已安装包缺少必需 IPC 服务时，采用与未安装包相同的升级路径。安装后重新检查 capability；无法读取 capability 响应时失败，不授予升级权限。

`OverlayLlmdIntegrationTest` 覆盖夹具翻译悬浮层、底层页面交互、真实原文与译文详情 UI，以及服务销毁后移除按钮和打开的详情。唯一夹具标题要求新的真实模型翻译，禁用模拟翻译。各用例打开所选 LLMD 授权页，核对调用方包后点击 Allow；真实 Binder 翻译证明授权成功。授权像此前一样持久保存。套件通过 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 保留其他无障碍服务。

该套件中的独立恢复用例覆盖 Nubia 分离式控制中心。它从右侧边缘下拉打开，确认焦点系统窗口为 `control_center`，再上滑关闭。检查返回页面和新变化文本的翻译，保存打开前、打开中和关闭后截图。它与通知栏不同，其他厂商设备跳过此用例。授权准备会清除先前授权页，核实调用方包，并调用可见原生 Allow 按钮的无障碍点击动作，避免依赖随系统 inset 改变的对话框坐标。

Android 13 及以上的翻译按钮共享一个全屏无障碍悬浮窗口。可触摸区域是各按钮边界的并集，间隙继续向底层应用传递输入。翻译详情面板另用一个窗口。这避免在控制中心等密集页面创建几十个独立系统窗口；实测 Nubia 设备因此前问题终止过 DiveDeep。更早 Android 版本保留逐按钮窗口实现，因为公开 surface 触摸区域 API 从 API 33 才可用。控制中心测试在 API 33+ 验证窗口数量有界。捕获忽略无障碍悬浮层事件，包括刚移除的悬浮窗口，因此按钮和详情更新不会重新启动翻译。详情窗口就地更新，不会为每个局部翻译帧删除重建。

捕获请求按窗口、包、文本和边界、翻译配置及目标语言去重。未变化输入的重复无障碍事件不能重置已完成或进行中的翻译。失败请求可以在后续事件重试，过时失败不能使新输入失效。当前根节点为无障碍悬浮层时，捕获使用其下方获得焦点的应用窗口。`TranslationRefreshGateTest` 覆盖这些请求转换。真实模型套件也会将详情保持打开五秒，同时检查结果和窗口身份，并单独检查关闭和重开。

页面其他内容变化时，渲染保留未变化源节点的成功结果。已移除节点、源文本变化和目标语言变化不能复用结果；配置变化清空渲染器。这些转换由 `TranslationFrameStateTest` 覆盖。不需要模型的 `TranslationSheetTest` 独立于真实 LLMD 集成，检查长内容滚动、固定操作和就地更新。

Android 11+ 的放置计算遵循系统栏和屏幕挖孔 inset。密集行使用相邻列，保证完整 48 dp 触摸目标互不重叠；没有空余列的按钮被省略，不堆叠。纯放置测试覆盖边缘列、碰撞和空间耗尽；真实设备套件检查可见夹具按钮没有相交的触摸边界。悬浮控件采用图像按钮，在 48 dp 透明触摸目标内部显示 24 dp 可见表面。独立真实设备用例点击透明留白，并验证打开详情中的预期原文，不只测试图标中心点击。

Android 向 `TranslationRefreshController` 注入内置 ML Kit 语言识别器。新节点可见或进入翻译后端之前，检测在自己的调度器上运行。同语言和未确定语言节点被排除；比较使用主要语言子标签，忽略地区和文字变体，并将 Android Bokmål（`nb`）映射到 ML Kit Norwegian（`no`）。检测失败使用现有刷新失败和重试路径。无障碍服务管理并关闭识别器。共享控制器测试注入检测器，无需 ML Kit 模型即可覆盖筛选和取消。`AndroidLanguageDetectorTest` 在设备检查内置模型。真实 IPC 夹具在系统语言为中文时选英语，否则选中文，确保语言门控仍要求真实外语文本翻译。

`TranslationRefreshController` 为每个页面管理可取消 job。语言检测在独立调度器运行，在分发翻译工作前，先在串行协调调度器发布符合条件的几何信息。旧后端阻塞不能阻止新页面检测或呈现。`TranslationRefreshBlockingTest` 用真实阻塞宿主 I/O 覆盖该行为。HTTP 请求使用异步 OkHttp 调用；协程取消会在响应体消费期间取消调用，所属服务关闭客户端。`TranslationHttpCallTest` 覆盖响应头到达前和读取正文期间取消，包括释放单个每宿主请求槽供下个请求使用。

替换页面或停止时取消旧 job，无需等待其后端；代次检查拒绝迟到帧和失败。共享测试包含忽略取消的后端。IPC 绑定同时管理待连接和待响应 deferred：断连、binding death、目标变化和关闭使待响应失败，调用方取消则移除其回调。`LlmdIpcLifecycleTest` 无需模型测试这些连接边界，与真实 IPC 测试保持独立。

真实提供方死亡回归应持有外部设备租约，使用已加载且就绪的模型运行独立套件：

```bash
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant debug --suite disconnect
```

此套件有意在新的未缓存请求期间停止 LLMD，验证待调用立即终止，再检查控件在五秒内跟随实际页面滚动。夹具明确可滚动；先检查源内容移动，再测试悬浮层移动。保存请求待完成、提供方停止和滚动后截图。套件结束后，复用设备前重新加载 LLMD 模型。宿主仍只恢复原始 DiveDeep 和设备快照；有意停止提供方是该套件明确的副作用。默认 `--suite overlay` 保留完整正常真实模型回归。

宿主设置设备端运行标记，提供匹配的 `hostRunId`、`realLlmd=true`、`llmdVariant` 和 class/filter instrumentation 参数。没有匹配宿主归属时，用例拒绝直接调用。用例结束时禁用 DiveDeep 并停止夹具，不恢复运行前快照；该责任仅属于 instrumentation 及其原生命令树停止后的宿主。

恢复按顺序验证：只禁用 DiveDeep 服务，确认其应用和夹具已停止，恢复私有偏好，恢复原始 secure settings，验证，再删除备份和运行标记。失败停止依赖写入。读取错误抛出异常，只有成功的显式探测才能证明不存在。Windows 使用 Job Object，Linux 使用专用子进程 subreaper，接管孤儿后代（含 Gradle 脱离的 single-use daemon），回收完整命令树后才确认关闭。宿主退出关闭控制管道并启动后代清理。监督器退出未经确认时，阻止设备恢复。其他宿主平台在启动命令前失败，需要等效进程作用域适配器。

二进制偏好副本保留在设备上。Base64 恢复副本和带类型日志保存在稳定的私有注册目录，绝不放入报告树。这些文件可能包含已保存 API 密钥。默认目录 Windows 为 `%LOCALAPPDATA%/DiveDeep/e2e/runs`，Linux 为 `${XDG_STATE_HOME:-~/.local/state}/DiveDeep/e2e/runs`，按设备序列号哈希和 run ID 索引。可用 `--state-dir` 或 `E2E_STATE_DIR` 覆盖，继续恢复时使用同一目录。宿主在修改设备前通过初始报告和 stdout 发布恢复路径。日志原子替换，并在对应副作用前刷盘。

回滚失败时，以 `needs-recovery` 状态退出（退出码 2），保留标记并打印私有日志路径。存在未解决标记或本地日志时拒绝新运行。设备重连后，获取租约并通过设备运行标记发现待恢复日志。若标记发布或移除被中断，则使用唯一待处理本地日志：

```sh
python scripts/android_e2e.py --device "$DEVICE" --recover
# Explicit paths remain supported for older journals or a different registry:
python scripts/android_e2e.py --device "$DEVICE" --recover /private/directory/run.json
```

无法确认宿主进程关闭时，恢复前先确认原运行器及其命令后代已停止。恢复要求设备和运行归属匹配，可继续被中断的回滚和释放。不要删除标记、备份或日志来绕过失败恢复。外部应用移除破坏设备备份时，保留宿主 Base64 副本供手动恢复。强制终止、宿主丢失和设备离线无法保证立即清理；保留的记录表示未解决工作。

结果：

- 宿主 `run.json`、Gradle 日志、限定范围诊断日志和原生 UI 产物：`test/e2e/reports/<run-id>/`，或 `--report-dir`/`E2E_REPORT_DIR`。
- JUnit XML：`androidApp/build/outputs/androidTest-results/connected/`。
- HTML：`androidApp/build/reports/androidTests/connected/`。
- 原生截图和层次结构来自应用外部 `files/e2e/<run-id>` 目录。授权截图按用例保留；失败用例还捕获 UI 层次与诊断日志。

验证后记录设备和 Android 版本、LLMD 变体及模型、命令、通过与失败数量，以及不可用的平台或模型组合。

### iOS 模拟器

**iOS Test** workflow（`.github/workflows/ios-test.yml`）在相关 pull request 和手动触发时，于 Apple Silicon `macos-15` runner 运行 `:shared:iosSimulatorArm64Test`，在 iOS 模拟器执行共享 common 测试并上传结果，不测试 SwiftUI 应用。

本地运行相同检查需要 Apple Silicon Mac，已选定 Xcode、接受许可证并安装 iOS 模拟器运行时：

```sh
./gradlew :shared:iosSimulatorArm64Test --warning-mode all --no-daemon
```

Windows 和 Linux 无法运行该任务。在这些宿主上任务禁用不代表 iOS 测试通过，应检查 macOS workflow 结果。

### Gradle 弃用诊断

使用 `--warning-mode all` 查看各条警告。添加 `-Dorg.gradle.deprecation.trace=true` 可包含调用位置（PowerShell 中为该参数加引号）。当前依赖剩余警告来自插件内部：

| 警告 | 弃用调用栈中的来源 |
| --- | --- |
| 不支持的 `HotReloadUsageType` 属性（Gradle 10） | Compose Hot Reload 1.2.0，`HotReloadUsage.kt` |
| `KotlinNativeBundleArtifactsTypes` 枚举属性（Gradle 10） | Kotlin Gradle plugin 2.4.21，`KotlinNativeBundleArtifactFormat.kt` |
| `Configuration.setVisible`（Gradle 11） | AGP 9.4.1、Detekt 2.0.0-alpha.6 和 KSP 2.3.12 的配置创建 |

Kotlin 警告中从 KGP 2.0 升级的通用建议不适用，本项目已使用 2.4.21。采用 Gradle 10/11 前，这些插件调用仍需兼容的上游版本。不要隐藏警告，也不在项目脚本修改插件内部实现。AGP 从 9.2.1 升级到 9.4.1 后，先前 Project 依赖记法警告已不再出现。
