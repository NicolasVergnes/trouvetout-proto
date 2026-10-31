plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

/** The verified embedding model (ADR-021), for `evalSearch`. */
val bundledModels by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
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
            val model = models.singleFile.resolve("models").listFiles().orEmpty().single { it.extension == "litertlm" }
            listOf(corpusDir, reportsDir, model.absolutePath) + extra.get().split(' ').filter { it.isNotBlank() }
        },
    )
}
