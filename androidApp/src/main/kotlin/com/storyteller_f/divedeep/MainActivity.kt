package com.storyteller_f.divedeep

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var translationConfig by mutableStateOf(TranslationConfig())
    private var blockedPackages by mutableStateOf<Set<String>>(emptySet())
    private var appEntries by mutableStateOf<List<AppEntry>>(emptyList())
    private var llmdAuthorizationMessage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appEntries = loadInstalledApps()
        collectSettings()

        setContent {
            DiveDeepSettingsScreen(
                enabled = enabled,
                config = translationConfig,
                blockedPackages = blockedPackages,
                appEntries = appEntries,
                llmdAuthorizationMessage = llmdAuthorizationMessage,
                onToggle = {
                    lifecycleScope.launch {
                        DiveDeepState.toggle(this@MainActivity)
                        DiveDeepTileService.requestTileRefresh(this@MainActivity)
                    }
                },
                onOpenAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onSaveConfig = { config ->
                    lifecycleScope.launch {
                        if (config.backend == TranslationBackend.LocalLlmdIpc && !isLlmdAuthorized(config)) {
                            llmdAuthorizationMessage =
                                "请先在 llmd ${config.llmdTarget.displayName} 中授权 DiveDeep"
                            openLlmdAuthorization(config.llmdTarget)
                            return@launch
                        }
                        DiveDeepState.setTranslationConfig(this@MainActivity, config)
                        llmdAuthorizationMessage = if (config.backend == TranslationBackend.LocalLlmdIpc) {
                            "已保存 llmd ${config.llmdTarget.displayName} 配置"
                        } else {
                            ""
                        }
                    }
                },
                onAuthorizeLlmd = { target ->
                    openLlmdAuthorization(target)
                },
                onPackageBlockedChange = { packageName, blocked ->
                    lifecycleScope.launch {
                        DiveDeepState.setPackageBlocked(this@MainActivity, packageName, blocked)
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        appEntries = loadInstalledApps()
        DiveDeepTileService.requestTileRefresh(this)
    }

    private fun collectSettings() {
        lifecycleScope.launch {
            DiveDeepState.initialize(this@MainActivity)
            DiveDeepState.settingsFlow(this@MainActivity).collect { settings ->
                enabled = settings.enabled
                translationConfig = settings.translationConfig
                blockedPackages = settings.blockedPackages
            }
        }
    }

    private fun openLlmdAuthorization(target: LlmdTarget) {
        val intent = Intent(LlmdIpcTranslationService.ACTION_AUTHORIZE_CALLER)
            .setPackage(target.packageName)
            .putExtra(LlmdIpcTranslationService.EXTRA_CALLER_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            llmdAuthorizationMessage = "未安装支持授权的 llmd ${target.displayName}"
        }
    }

    private suspend fun isLlmdAuthorized(config: TranslationConfig): Boolean =
        withContext(Dispatchers.IO) {
            val service = LlmdIpcTranslationService(this@MainActivity) { config }
            try {
                val response = service.health()
                val body = JSONObject(response)
                body.optJSONObject("error")?.optString("type") != "authorization_required" &&
                    body.optString("status") == "ok"
            } catch (_: TranslationException) {
                false
            } finally {
                service.close()
            }
        }

    private fun loadInstalledApps(): List<AppEntry> =
        packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .map { info ->
                AppEntry(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.packageName,
                )
            }
            .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
}

@Composable
internal fun DiveDeepSettingsScreen(
    enabled: Boolean,
    config: TranslationConfig,
    blockedPackages: Set<String>,
    appEntries: List<AppEntry>,
    llmdAuthorizationMessage: String,
    onToggle: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onSaveConfig: (TranslationConfig) -> Unit,
    onAuthorizeLlmd: (LlmdTarget) -> Unit,
    onPackageBlockedChange: (String, Boolean) -> Unit,
) {
    var backend by remember(config) { mutableStateOf(config.backend) }
    var llmdTarget by remember(config) { mutableStateOf(config.llmdTarget) }
    var apiBaseUrl by remember(config) { mutableStateOf(config.apiBaseUrl) }
    var model by remember(config) { mutableStateOf(config.model) }
    val apiKey = remember(config) { TextFieldState(config.apiKey) }
    var useMockTranslation by remember(config) { mutableStateOf(config.useMockTranslation) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    OverviewSection(
                        enabled = enabled,
                        onToggle = onToggle,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    )
                }

                item {
                    BackendSection(
                        backend = backend,
                        onBackendChange = { backend = it },
                        llmdTarget = llmdTarget,
                        onLlmdTargetChange = { llmdTarget = it },
                        authorizationMessage = llmdAuthorizationMessage,
                        onAuthorizeLlmd = { onAuthorizeLlmd(llmdTarget) },
                    )
                }

                item {
                    TranslationTextField(
                        value = apiBaseUrl,
                        onValueChange = { apiBaseUrl = it },
                        enabled = backend == TranslationBackend.OpenAiHttp,
                        label = "API Base URL",
                    )
                }

                item {
                    TranslationTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "Model",
                    )
                }

                item {
                    OutlinedSecureTextField(
                        state = apiKey,
                        enabled = backend == TranslationBackend.OpenAiHttp,
                        label = { Text("API Key") },
                        textObfuscationMode = TextObfuscationMode.Hidden,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    MockTranslationToggle(
                        checked = useMockTranslation,
                        onCheckedChange = { useMockTranslation = it },
                    )
                }

                item {
                    SaveTranslationConfigButton(
                        backend = backend,
                        llmdTarget = llmdTarget,
                        apiBaseUrl = apiBaseUrl,
                        model = model,
                        apiKey = apiKey.text.toString(),
                        useMockTranslation = useMockTranslation,
                        onSaveConfig = onSaveConfig,
                    )
                }

                item {
                    SectionTitle("不翻译应用")
                }

                items(appEntries, key = { it.packageName }) { app ->
                    AppFilterRow(
                        app = app,
                        blocked = app.packageName in blockedPackages,
                        onBlockedChange = { blocked ->
                            onPackageBlockedChange(app.packageName, blocked)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeaderSection(enabled)
        ActionSection(
            enabled = enabled,
            onToggle = onToggle,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        )
    }
}

@Composable
private fun HeaderSection(enabled: Boolean) {
    Text("DiveDeep", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        if (enabled) {
            "翻译开关已开启。请确认无障碍服务已授权。"
        } else {
            "翻译开关已关闭。你也可以从快捷设置瓷贴开启。"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ActionSection(
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (enabled) "停止翻译" else "开始翻译")
        }
        Button(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开无障碍设置")
        }
    }
}

@Composable
private fun BackendSection(
    backend: TranslationBackend,
    onBackendChange: (TranslationBackend) -> Unit,
    llmdTarget: LlmdTarget,
    onLlmdTargetChange: (LlmdTarget) -> Unit,
    authorizationMessage: String,
    onAuthorizeLlmd: () -> Unit,
) {
    SectionTitle("翻译配置")
    BackendOption(
        selected = backend == TranslationBackend.LocalLlmdIpc,
        text = "本机 llmd IPC",
        onClick = { onBackendChange(TranslationBackend.LocalLlmdIpc) },
    )
    if (backend == TranslationBackend.LocalLlmdIpc) {
        Text("llmd 版本", style = MaterialTheme.typography.titleMedium)
        LlmdTarget.entries.forEach { target ->
            BackendOption(
                selected = llmdTarget == target,
                text = target.displayName,
                onClick = { onLlmdTargetChange(target) },
            )
        }
        Button(onClick = onAuthorizeLlmd) {
            Text("授权 llmd ${llmdTarget.displayName}")
        }
        if (authorizationMessage.isNotBlank()) {
            Text(authorizationMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
    BackendOption(
        selected = backend == TranslationBackend.OpenAiHttp,
        text = "HTTP / HTTPS API",
        onClick = { onBackendChange(TranslationBackend.OpenAiHttp) },
    )
}

@Composable
private fun TranslationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MockTranslationToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text("使用 Mock 翻译")
    }
}

@Composable
private fun SaveTranslationConfigButton(
    backend: TranslationBackend,
    llmdTarget: LlmdTarget,
    apiBaseUrl: String,
    model: String,
    apiKey: String,
    useMockTranslation: Boolean,
    onSaveConfig: (TranslationConfig) -> Unit,
) {
    Button(
        onClick = {
            onSaveConfig(
                TranslationConfig(
                    backend = backend,
                    llmdTarget = llmdTarget,
                    apiBaseUrl = apiBaseUrl.trim(),
                    model = model.trim(),
                    apiKey = apiKey.trim(),
                    useMockTranslation = useMockTranslation,
                ),
            )
        },
    ) {
        Text("保存翻译配置")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun BackendOption(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text)
    }
}

@Composable
private fun AppFilterRow(
    app: AppEntry,
    blocked: Boolean,
    onBlockedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(checked = blocked, onCheckedChange = onBlockedChange)
        }
        HorizontalDivider()
    }
}

internal data class AppEntry(
    val label: String,
    val packageName: String,
)

@Preview
@Composable
private fun DiveDeepSettingsPreview() {
    DiveDeepSettingsScreen(
        enabled = false,
        config = TranslationConfig(),
        blockedPackages = setOf("com.android.launcher"),
        appEntries = listOf(
            AppEntry("设置", "com.android.settings"),
            AppEntry("Fixture", "com.storyteller_f.divedeep.fixture"),
        ),
        llmdAuthorizationMessage = "",
        onToggle = {},
        onOpenAccessibilitySettings = {},
        onSaveConfig = {},
        onAuthorizeLlmd = {},
        onPackageBlockedChange = { _, _ -> },
    )
}
