<a id="project-rules"></a>

# 项目规则

<a id="general"></a>

## 通用规则

- 将改动限制在请求的行为范围内，不在功能或测试修改中混入无关重构。
- 优先遵循仓库已有 Kotlin、Gradle、Compose 和 shell 脚本模式。
- 共享模块当前只面向 Android、iOS 和 JVM。JS/WASM Web target 已禁用，因为共享 `commonMain` 使用 Room/SQLite 缓存翻译。
- 修改代码后，运行能覆盖受影响行为的最小检查。共享 Android、IPC、workflow 或端到端行为变化时，扩大到下文完整检查。

<a id="testing"></a>

## 测试

交付 Android/Kotlin 修改前，运行以下基线检查：

```bash
./gradlew detekt :shared:jvmTest :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidTestFixture:assembleDebug --no-daemon
```

Android 测试架构、前提、命令、报告和恢复流程遵循 [DEVELOPMENT.md](DEVELOPMENT.md#android-end-to-end-tests)。

- 每项设备操作都通过环境共享的设备锁辅助程序串行化。使用其已安装的绝对路径，不假定本仓库内置辅助程序。
- 保留真实跨应用 LLMD IPC 覆盖。不得用模拟翻译替代 `OverlayLlmdIntegrationTest`；不需要模型的回归测试是独立套件。
- 宿主运行器管理设备和应用的原始快照；Kotlin 用例只管理其临时基线。保留该边界，前提失败时停止回滚。
- 修改宿主 E2E 后，运行 `python -m unittest discover -s test/e2e/unit -p 'test_*.py' -v`。这些有状态边界与进程测试不访问设备。
- 修改宿主运行器或平台适配器时，保留跨进程崩溃恢复和脱离父进程的后代进程测试。
- `needs-recovery` 表示尚未解决的工作。保留私有日志和备份，在外部设备租约内恢复，验证后再复用设备。
- 修改 Android 测试或构建后，还需编译 `:androidApp:assembleDebugAndroidTest`，在所需设备和模型可用时运行受影响设备用例。
- 完整构建 LLMD APK 时，不要直接从其生成的 Android 目录运行 Gradle。按 DEVELOPMENT.md 使用 Tauri CLI。
