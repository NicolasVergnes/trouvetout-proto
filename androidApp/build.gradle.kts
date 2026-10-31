import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** `-Ptrouvetout.bundleModel=false` builds without the embedding model: keyword search only, lighter installs. */
val bundleModel = providers.gradleProperty("trouvetout.bundleModel").map(String::toBoolean).getOrElse(true)

@Suppress("UNCHECKED_CAST")
val textModel =
    (JsonSlurper().parse(rootProject.file("models/manifest.json")) as Map<String, Any>)["models"]
        .let { it as List<Map<String, Any>> }
        .single { it["variant"] == "TEXT_ONLY" }

android {
    namespace = "app.trouvetout.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Neutral prototype identifier (ADR-010); the public one is chosen before publication.
        applicationId = "app.trouvetout.proto"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The bundled model (ADR-021): asset path (null without it), identity (name + checksum prefix), size.
        buildConfigField("String", "TEXT_MODEL_ASSET", if (bundleModel) "\"${textModel["file"]}\"" else "null")
        buildConfigField(
            "String",
            "TEXT_MODEL_ID",
            "\"${textModel["id"]}@${(textModel["sha256"] as String).take(12)}\"",
        )
        buildConfigField("long", "TEXT_MODEL_BYTES", "${textModel["bytes"]}L")
    }

    // Stored as is: LiteRT-LM needs the plain file, copied once from the APK on first use.
    androidResources {
        noCompress += "litertlm"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Also analyses shared/androidMain (the KMP library has no lint of its own).
        checkDependencies = true
    }

    testOptions {
        // Platform tests (driver, image codec, U1 smoke) on the minimum supported system, in CI (lot 4).
        managedDevices {
            localDevices {
                create("api29") {
                    device = "Pixel 2"
                    apiLevel = 29
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

/** The verified model directory produced by `:models:fetchTextModel`. */
val bundledModels by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/** Copies the verified model into a generated assets directory of every variant. */
abstract class CopyModelAssets : DefaultTask() {
    @get:InputFiles
    abstract val models: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        models.forEach { it.copyRecursively(out, overwrite = true) }
    }
}

val copyModelAssets =
    tasks.register<CopyModelAssets>("copyModelAssets") {
        models.from(bundledModels)
        outputDirectory.set(layout.buildDirectory.dir("generated/modelAssets"))
    }

if (bundleModel) {
    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(copyModelAssets, CopyModelAssets::outputDirectory)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    if (bundleModel) bundledModels(project(mapOf("path" to ":models", "configuration" to "bundledModels")))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
