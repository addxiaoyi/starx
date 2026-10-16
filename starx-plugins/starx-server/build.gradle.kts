import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    `java-library`
    id("com.gradleup.shadow")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val serverPluginVersion = project.version.toString()

val paper261ApiClasspath = configurations.create("paper261ApiClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
}
val paper262ApiClasspath = configurations.create("paper262ApiClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
}

dependencies {
    implementation(project(":starx-plugins:starx-api"))
    implementation(project(":starx-plugins:starx-website-sync"))
    implementation(project(":starx-plugins:starx-extension-runtime"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.zxing:core:3.5.3")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.7")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("me.clip:placeholderapi:2.11.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(paper261ApiClasspath.name, "io.papermc.paper:paper-api:26.1.2.build.71-stable")
    add(paper261ApiClasspath.name, "me.clip:placeholderapi:2.11.7")
    add(paper261ApiClasspath.name, "org.jetbrains:annotations:26.1.0")
    add(paper262ApiClasspath.name, "io.papermc.paper:paper-api:26.2.build.84-stable")
    add(paper262ApiClasspath.name, "me.clip:placeholderapi:2.11.7")
    add(paper262ApiClasspath.name, "org.jetbrains:annotations:26.1.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", serverPluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to serverPluginVersion)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName.set("starx-server.jar")
    exclude("META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.SF")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

fun registerPaper26Compile(
    taskName: String,
    apiVersion: String,
    classpath: Configuration
) = tasks.register<JavaCompile>(taskName) {
    group = "verification"
    description = "Compiles the backend against Paper $apiVersion with Java 25"
    source(sourceSets.main.get().allJava)
    this.classpath = classpath
    destinationDirectory.set(
        layout.buildDirectory.dir("paper-api-compat/$apiVersion/classes")
    )
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    options.release.set(21)
    options.encoding = "UTF-8"
}

val verifyPaper261Api = registerPaper26Compile(
    "verifyPaper261Api", "26.1.2.build.71-stable", paper261ApiClasspath
)
val verifyPaper262Api = registerPaper26Compile(
    "verifyPaper262Api", "26.2.build.84-stable", paper262ApiClasspath
)

tasks.check {
    dependsOn(verifyPaper261Api, verifyPaper262Api)
}
