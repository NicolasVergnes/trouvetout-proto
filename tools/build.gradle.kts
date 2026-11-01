plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

/** The verified embedding models (ADR-021, ADR-022), for `evalSearch`, `evalPhotos` and `genLabels`. */
val bundledModels by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // JVM build of the runtime the apps embed: the same model file, prompts and normalisation (ADR-021).
    implementation(libs.litertlm.jvm)
    testImplementation(libs.kotlin.test)
    bundledModels(project(mapOf("path" to ":models", "configuration" to "bundledModels")))
}

val evalReportsDir = rootProject.layout.buildDirectory.dir("reports/eval")

tasks.register<JavaExec>("evalParser") {
    group = "evaluation"
    description = "Replays corpus/parser.jsonl through the capture parser and fails under the thresholds (lot 3)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.trouvetout.tools.EvalParserKt")
    args(rootProject.file("corpus/parser.jsonl").absolutePath, evalReportsDir.get().asFile.absolutePath)
}

tasks.register<JavaExec>("evalSearch") {
    group = "evaluation"
    description = "Replays corpus/recherche.jsonl against the reference items and fails under the thresholds (lot 6)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.trouvetout.tools.EvalSearchKt")
    // LiteRT-LM's JVM classes target Java 21; the rest of the build stays on 17.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    inputs.files(bundledModels)
    val models: FileCollection = bundledModels
    val corpusDir = rootProject.file("corpus").absolutePath
    val reportsDir = evalReportsDir.get().asFile.absolutePath
    // Extra options, e.g. -PevalArgs="--mode keyword --prompt MODEL_CARD --dim 768 --no-latency".
    val extra = providers.gradleProperty("evalArgs").orElse("")
    argumentProviders.add(
        CommandLineArgumentProvider {
            val model = models.singleFile.resolve("models/embeddinggemma-2-text-270m.litertlm")
            listOf(corpusDir, reportsDir, model.absolutePath) + extra.get().split(' ').filter { it.isNotBlank() }
        },
    )
}

tasks.register<JavaExec>("genLabels") {
    group = "models"
    description = "Computes models/labels_fr_en.catalog, the label vectors bundled for photo naming (B8, lot 7)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.trouvetout.tools.GenLabelsKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    inputs.files(bundledModels)
    val models: FileCollection = bundledModels
    val modelsDir = rootProject.file("models").absolutePath
    // e.g. -PlabelTemplate=fr+en
    val template = providers.gradleProperty("labelTemplate").orElse("")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(modelsDir, models.singleFile.absolutePath) +
                template.get().takeIf { it.isNotBlank() }?.let { listOf("--template", it) }.orEmpty()
        },
    )
}

tasks.register<JavaExec>("evalPhotos") {
    group = "evaluation"
    description = "Names the CC BY photos of corpus/photos-cc.jsonl and fails under the floors (B8, lot 7)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.trouvetout.tools.EvalPhotosKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    inputs.files(bundledModels)
    val models: FileCollection = bundledModels
    val corpusDir = rootProject.file("corpus").absolutePath
    val reportsDir = evalReportsDir.get().asFile.absolutePath
    val modelsDir = rootProject.file("models").absolutePath
    // Downloaded images live outside the sources (cached by CI), never committed.
    val imageCache = rootProject.layout.buildDirectory.dir("eval-photos").get().asFile.absolutePath
    // e.g. -PevalArgs="--templates" or -PevalArgs="--local D:/photos-maison"
    val extra = providers.gradleProperty("evalArgs").orElse("")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(corpusDir, reportsDir, modelsDir, models.singleFile.absolutePath, imageCache) +
                extra.get().split(' ').filter { it.isNotBlank() }
        },
    )
}

tasks.test {
    // LabelCatalogFreshnessTest reads models/ from the repository root.
    systemProperty("trouvetout.root", rootProject.projectDir.absolutePath)
}
