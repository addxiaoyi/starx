import java.util.jar.JarFile

plugins {
    java
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val velocityBuild606Compile = files(
    rootProject.tasks.named("prepareVelocityBuild606Compile")
)

val velocityBuild606Runtime = files(
    rootProject.layout.projectDirectory.file("vendor/velocity/velocity-3.5.0-SNAPSHOT-606.jar")
)
val extensionVersion = project.version.toString()

dependencies {
    compileOnly(project(":starx-plugins:starx-api"))
    compileOnly(velocityBuild606Compile)
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation(project(":starx-plugins:starx-api"))
    testImplementation(project(":starx-plugins:starx-extension-runtime"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly(velocityBuild606Runtime)
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", extensionVersion)
    filesMatching(listOf("velocity-plugin.json", "plugin.yml")) {
        expand("version" to extensionVersion)
    }
}

tasks.jar {
    archiveFileName.set("starx-extension-example.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyExampleJar = tasks.register("verifyExampleJar") {
    group = "verification"
    description = "Verifies the public-API-only three-platform example extension JAR"
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })

    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        val entries = JarFile(artifact).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }
        val required = listOf(
            "velocity-plugin.json",
            "plugin.yml",
            "io/github/addxiaoyi/starx/example/ExampleStarxExtension.class",
            "io/github/addxiaoyi/starx/example/velocity/StarxExampleVelocityPlugin.class",
            "io/github/addxiaoyi/starx/example/server/StarxExampleServerPlugin.class"
        )
        val missing = required.filterNot(entries::contains)
        if (missing.isNotEmpty()) {
            throw GradleException("Example extension JAR is missing entries: $missing")
        }

        val forbiddenPrefixes = listOf(
            "io/github/addxiaoyi/starx/api/",
            "com/velocitypowered/",
            "org/bukkit/",
            "io/papermc/"
        )
        val leaked = entries.filter { entry -> forbiddenPrefixes.any(entry::startsWith) }
        if (leaked.isNotEmpty()) {
            throw GradleException("Example extension embeds provided API classes: ${leaked.take(20)}")
        }
        val forbiddenEntries = entries.filter { entry ->
            entry.endsWith(".jar") ||
                entry == "module-info.class" ||
                entry.endsWith("/module-info.class") ||
                entry.matches(Regex("META-INF/.*\\.(RSA|DSA|SF)"))
        }
        if (forbiddenEntries.isNotEmpty()) {
            throw GradleException("Example extension contains forbidden entries: $forbiddenEntries")
        }
        if (entries.groupingBy { it }.eachCount().any { it.value != 1 }) {
            throw GradleException("Example extension contains duplicate ZIP entries")
        }

        JarFile(artifact).use { jar ->
            val velocity = jar.getInputStream(jar.getJarEntry("velocity-plugin.json"))
                .reader(Charsets.UTF_8).use { it.readText() }
            val backend = jar.getInputStream(jar.getJarEntry("plugin.yml"))
                .reader(Charsets.UTF_8).use { it.readText() }
            if (!velocity.contains("\"id\": \"starx\"") ||
                !velocity.contains("StarxExampleVelocityPlugin")) {
                throw GradleException("Velocity descriptor does not hard-depend on StarX")
            }
            if (!backend.contains("depend: [StarXServer]") ||
                !backend.contains("folia-supported: true") ||
                !backend.contains("StarxExampleServerPlugin")) {
                throw GradleException("Backend descriptor is missing StarX/Folia contract")
            }
        }

        logger.lifecycle("STARX_EXTENSION_EXAMPLE_JAR=${artifact.absolutePath}")
        logger.lifecycle("STARX_EXTENSION_EXAMPLE_VERIFICATION=PASS")
    }
}

tasks.check {
    dependsOn(verifyExampleJar)
}
