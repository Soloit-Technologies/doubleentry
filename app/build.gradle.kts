import com.android.build.api.dsl.VariantDimension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Supabase project settings are read from local.properties (git-ignored) or, for CI,
// from environment variables. See README.md → "Configure Supabase".
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(key: String, env: String): String =
    (localProperties.getProperty(key) ?: System.getenv(env) ?: "").trim()

val supabaseUrl = config("supabase.url", "SUPABASE_URL")
val supabaseAnonKey = config("supabase.anonKey", "SUPABASE_ANON_KEY")
// The OAuth "Web application" client id that Sign in with Google issues tokens for (README → "Sign in with Google").
val googleWebClientId = config("google.webClientId", "GOOGLE_WEB_CLIENT_ID")
// Cloud sync is on in debug builds and off in release (Google Play) builds unless set explicitly.
val cloudSyncSetting = config("cloudSync.enabled", "CLOUD_SYNC_ENABLED").ifEmpty { null }?.toBoolean()

// Builds without cloud sync don't embed the Supabase project at all.
fun VariantDimension.cloudSync(default: Boolean) {
    val enabled = cloudSyncSetting ?: default
    // A store build with cloud backup but no project would show "not set up" to customers.
    check(!enabled || default || (supabaseUrl.isNotEmpty() && supabaseAnonKey.isNotEmpty() && googleWebClientId.isNotEmpty())) {
        "cloudSync.enabled is true, but supabase.url / supabase.anonKey / google.webClientId " +
            "(SUPABASE_URL / SUPABASE_ANON_KEY / GOOGLE_WEB_CLIENT_ID) are missing."
    }
    buildConfigField("String", "SUPABASE_URL", "\"${if (enabled) supabaseUrl else ""}\"")
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${if (enabled) supabaseAnonKey else ""}\"")
    buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${if (enabled) googleWebClientId else ""}\"")
    buildConfigField("boolean", "CLOUD_SYNC_FEATURE", enabled.toString())
}

// Optional release signing: keystore.properties (git-ignored) with storeFile, storePassword,
// keyAlias and keyPassword, or for CI the SIGNING_* environment variables.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signing(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)?.ifBlank { null }

android {
    namespace = "com.soloit.doubleentry"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.soloit.doubleentry"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()

        manifestPlaceholders["appLabel"] = "DoubleEntry"
    }

    signingConfigs {
        signing("storeFile", "SIGNING_STORE_FILE")?.let { path ->
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = signing("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signing("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs side by side with the release build.
            applicationIdSuffix = ".dev"
            cloudSync(default = true)
            manifestPlaceholders["appLabel"] = "DoubleEntry Dev"
        }
        release {
            cloudSync(default = false)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric runs the tests of Android-only code (billing).
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.google.play.billing)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
