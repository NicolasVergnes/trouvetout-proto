// Google Play delivery of the models (lot 12, ADR-027): an install-time asset pack, downloaded by Play with the app
// and read through the AssetManager at the same paths as the embedded assets, so the app's code does not change. No
// library, no network permission. Included only with `-Ptrouvetout.modelDelivery=pack` (settings.gradle.kts), for
// the App Bundle: the debug APKs and the device tests keep the models in the base APK.
plugins {
    // From AGP, already on the build classpath through the root project: no new artifact to verify.
    id("com.android.asset-pack")
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

/** The verified model directory produced by `:models:fetchModels`. */
val bundledModels by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundledModels(project(mapOf("path" to ":models", "configuration" to "bundledModels")))
}

/**
 * Same files, same paths as the app's `copyModelAssets`: the verified `.litertlm` files (never a partial download)
 * and the label catalog, under `models/`. The asset-pack plugin reads `src/main/assets` only; the copy is ignored by
 * git (`*.litertlm`) and rewritten whole at each build.
 */
val syncModels =
    tasks.register<Sync>("syncModels") {
        from(bundledModels) { include("**/*.litertlm") }
        from(rootProject.layout.projectDirectory.file("models/labels_fr_en.catalog")) { into("models") }
        into(layout.projectDirectory.dir("src/main/assets"))
    }

tasks.configureEach {
    if (name != syncModels.name && !name.startsWith("clean") && !name.startsWith("ktlint") && !name.startsWith("detekt")) {
        dependsOn(syncModels)
    }
}
