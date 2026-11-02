import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** `-Ptrouvetout.bundleModel=false` builds without the embedding model: keyword search only, lighter installs. */
val bundleModel = providers.gradleProperty("trouvetout.bundleModel").map(String::toBoolean).getOrElse(true)

@Suppress("UNCHECKED_CAST")
val manifestModels =
    (JsonSlurper().parse(rootProject.file("models/manifest.json")) as Map<String, Any>)["models"]
        .let { it as List<Map<String, Any>> }
val textModel = manifestModels.single { it["variant"] == "TEXT_ONLY" }
val visionModel = manifestModels.single { it["variant"] == "OMNIMODAL" }

/** Identity of a bundled file, as the core compares it: name and checksum prefix. */
fun identity(model: Map<String, Any>): String = "${model["id"]}@${(model["sha256"] as String).take(12)}"

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
        // SQLCipher ships four ABIs; LiteRT-LM two. A 32-bit ARM phone installs and searches by keywords
        // (ADR-021, ADR-023); 32-bit x86 has no phone to serve.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // The bundled models (ADR-021, ADR-022): asset path (null without it), identity (name + checksum prefix),
        // size; then the label catalog of photo naming (B8), computed by `:tools:genLabels`.
        buildConfigField("String", "TEXT_MODEL_ASSET", if (bundleModel) "\"${textModel["file"]}\"" else "null")
        buildConfigField("String", "TEXT_MODEL_ID", "\"${identity(textModel)}\"")
        buildConfigField("long", "TEXT_MODEL_BYTES", "${textModel["bytes"]}L")
        buildConfigField("String", "VISION_MODEL_ASSET", if (bundleModel) "\"${visionModel["file"]}\"" else "null")
        buildConfigField("String", "VISION_MODEL_ID", "\"${identity(visionModel)}\"")
        buildConfigField("long", "VISION_MODEL_BYTES", "${visionModel["bytes"]}L")
        buildConfigField("String", "LABELS_ASSET", if (bundleModel) "\"models/labels_fr_en.catalog\"" else "null")
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
                    // x86_64 image: LiteRT-LM ships no 32-bit x86 native library (ADR-021).
                    require64Bit = true
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

/** The verified model directory produced by `:models:fetchModels`. */
val bundledModels by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/** Copies the verified models and the label catalog into a generated assets directory of every variant. */
abstract class CopyModelAssets : DefaultTask() {
    @get:InputFiles
    abstract val models: ConfigurableFileCollection

    /** `models/labels_fr_en.catalog` (committed, small, derived data: no weights). */
    @get:InputFile
    abstract val labels: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        // Only the verified model files (never a partial download left in the directory).
        models.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "litertlm" }.forEach { file ->
                file.copyTo(File(out, file.relativeTo(root).path), overwrite = true)
            }
        }
        labels.get().asFile.copyTo(File(out, "models/labels_fr_en.catalog"), overwrite = true)
    }
}

val copyModelAssets =
    tasks.register<CopyModelAssets>("copyModelAssets") {
        models.from(bundledModels)
        labels.set(rootProject.layout.projectDirectory.file("models/labels_fr_en.catalog"))
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
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    // A plain database as lots 1 to 7 wrote it, for the migration test of the encryption (H2).
    androidTestImplementation(libs.sqldelight.android.driver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
