plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// The ledger logic every app shares: domain rules, the Room database, repositories and cloud sync.
kotlin {
    androidLibrary {
        namespace = "com.soloit.doubleentry.shared"
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    compilerOptions {
        // Room generates an actual AppDatabaseConstructor object for every platform.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            // Snapshot state only (no UI): some screens keep text field input in it.
            implementation(libs.compose.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}
