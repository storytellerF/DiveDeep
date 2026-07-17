import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val signPath: String? = System.getenv("storyteller_f_sign_path")
val signKey: String? = System.getenv("storyteller_f_sign_key")
val signAlias: String? = System.getenv("storyteller_f_sign_alias")
val signStorePassword: String? = System.getenv("storyteller_f_sign_store_password")
val signKeyPassword: String? = System.getenv("storyteller_f_sign_key_password")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtimeKtx)

    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.coroutinesAndroid)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.storyteller_f.divedeep"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.storyteller_f.divedeep"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val releaseSignConfig = signingConfigs.findByName("release")
            if (releaseSignConfig != null) {
                signingConfig = releaseSignConfig
            }
        }
        create("daily") {
            initWith(getByName("release"))
            applicationIdSuffix = ".daily"
            versionNameSuffix = "-daily"
            matchingFallbacks += listOf("release")
        }
    }
    signingConfigs {
        val signStorePath = when {
            signPath != null -> File(signPath)
            signKey != null -> File(System.getProperty("user.home"), "signing_key.jks")
            else -> null
        }
        if (signStorePath != null && signAlias != null && signStorePassword != null && signKeyPassword != null) {
            create("release") {
                keyAlias = signAlias
                keyPassword = signKeyPassword
                storeFile = signStorePath
                storePassword = signStorePassword
            }
        }
    }
    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
