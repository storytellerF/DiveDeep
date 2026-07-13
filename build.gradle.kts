plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

subprojects {
    fun configureDetekt() {
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            parallel = true
            basePath.set(rootProject.projectDir)
        }

        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            jvmTarget.set("11")
            setSource(files("src"))
            reports {
                html.required.set(true)
                markdown.required.set(true)
                sarif.required.set(true)
            }
            include("**/*.kt", "**/*.kts")
            exclude("**/build/**", "**/generated/**")
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "dev.detekt")
        configureDetekt()
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "dev.detekt")
        configureDetekt()
    }
    pluginManager.withPlugin("com.android.application") {
        apply(plugin = "dev.detekt")
        configureDetekt()
    }
    pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
        apply(plugin = "dev.detekt")
        configureDetekt()
    }
}
