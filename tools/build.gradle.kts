plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
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
    args(rootProject.file("corpus").absolutePath, evalReportsDir.get().asFile.absolutePath)
}
