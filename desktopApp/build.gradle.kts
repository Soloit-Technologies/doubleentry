import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// The same Supabase settings as the Android app: local.properties (git-ignored) or, for CI,
// environment variables. See README.md → "Configure Supabase". Without them cloud sync is hidden.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(key: String, env: String): String =
    (localProperties.getProperty(key) ?: System.getenv(env) ?: "").trim()

val appVersionName = providers.gradleProperty("app.versionName").get()

val generateBuildConfig by tasks.registering(GenerateBuildConfig::class) {
    supabaseUrl = config("supabase.url", "SUPABASE_URL")
    supabaseAnonKey = config("supabase.anonKey", "SUPABASE_ANON_KEY")
    cloudSync = config("cloudSync.enabled", "CLOUD_SYNC_ENABLED").ifEmpty { "true" }.toBoolean()
    versionCode = providers.gradleProperty("app.versionCode").get().toInt()
    versionName = appVersionName
    outputDirectory = layout.buildDirectory.dir("generated/buildConfig")
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generateBuildConfig)
    }
    compilerOptions {
        optIn.addAll(
            "io.github.composefluent.ExperimentalFluentApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.fluent)
    implementation(libs.compose.fluent.icons)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "com.soloit.doubleentry.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.d3d=true")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "DoubleEntry"
            packageVersion = appVersionName
            description = "Double-entry bookkeeping"
            vendor = "Soloit Technologies AB"
            copyright = "Copyright © Soloit Technologies AB. Mozilla Public License 2.0."
            // From suggestRuntimeModules, plus TLS with elliptic curves (Supabase over HTTPS) and the
            // Java Access Bridge, which screen readers on Windows use. jdk.httpserver receives the
            // sign-in redirect.
            modules("java.instrument", "jdk.httpserver", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")

            windows {
                iconFile = project.file("icons/DoubleEntry.ico")
                menuGroup = "DoubleEntry"
                perUserInstall = true
                dirChooser = false
                shortcut = true
                // Keep this id: Windows Installer upgrades the installed app by it.
                upgradeUuid = "6f0b1a9e-3c7d-4e52-9a61-2b8d4c0e7f15"
            }
            macOS {
                iconFile = project.file("icons/DoubleEntry.icns")
                bundleID = "com.soloit.doubleentry.desktop"
            }
            linux {
                iconFile = project.file("icons/DoubleEntry.png")
            }
        }

        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}

/** Writes BuildConfig.kt with this build's settings, like the Android app's BuildConfig. */
abstract class GenerateBuildConfig : DefaultTask() {
    @get:Input abstract val supabaseUrl: Property<String>
    @get:Input abstract val supabaseAnonKey: Property<String>
    @get:Input abstract val cloudSync: Property<Boolean>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val enabled = cloudSync.get()
        val file = outputDirectory.get().file("com/soloit/doubleentry/desktop/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        file.writeText(
            """
            |package com.soloit.doubleentry.desktop
            |
            |/** Generated from local.properties / the environment by desktopApp/build.gradle.kts. */
            |object BuildConfig {
            |    const val SUPABASE_URL = ${quote(if (enabled) supabaseUrl.get() else "")}
            |    const val SUPABASE_ANON_KEY = ${quote(if (enabled) supabaseAnonKey.get() else "")}
            |    const val CLOUD_SYNC_FEATURE = $enabled
            |    const val VERSION_CODE = ${versionCode.get()}
            |    const val VERSION_NAME = ${quote(versionName.get())}
            |}
            |""".trimMargin(),
        )
    }
}
