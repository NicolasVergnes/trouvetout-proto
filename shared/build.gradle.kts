import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    android {
        namespace = "app.trouvetout.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest { }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "app.trouvetout.proto.shared")
        }
    }

    // Default hierarchy plus a `jvmCommon` source set shared by the Android and JVM targets
    // (java.io, java.security, JDBC): JVM actuals are written once.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    // Kotlin/Native simulator tests: the CI exports the UDID of an available iPhone; locally the name suffices.
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(providers.environmentVariable("IOS_SIMULATOR_DEVICE").orElse("iPhone 16"))
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM and Android host tests open a real in-memory SQLite through the JDBC driver.
        getByName("jvmCommonTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        // The template attaches jvmCommon to the JVM target; the Android KMP library target is linked explicitly.
        androidMain.get().dependsOn(getByName("jvmCommonMain"))
        getByName("androidHostTest").dependsOn(getByName("jvmCommonTest"))
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("TrouveToutDb") {
            packageName.set("app.trouvetout.core.data.db")
            // SQLite 3.25 grammar: the floor shipped by Android 10 (3.28); iOS 16 and the JVM driver are newer.
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
