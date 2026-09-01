import java.util.jar.JarFile

plugins {
    java
    `java-library`
    id("com.gradleup.shadow")
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
val velocityPluginVersion = project.version.toString()

dependencies {
    // starx-common 必须 shadow 到 jar 中，否则运行时找不到类
    implementation(project(":starx-plugins:starx-common"))
    implementation(project(":starx-plugins:starx-website-sync"))
    implementation(project(":starx-plugins:starx-extension-runtime"))
    implementation(project(":starx-plugins:starx-standalone-limbo"))
    implementation("com.typesafe:config:1.4.3")

    // Uworld uses Velocity internals, so the compiler must match the validated runtime build.
    compileOnly(velocityBuild606Compile)

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly(velocityBuild606Compile)
    testRuntimeOnly(velocityBuild606Runtime)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("starx.project.version", velocityPluginVersion)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", velocityPluginVersion)
    filesMatching("velocity-plugin.json") {
        expand("version" to velocityPluginVersion)
    }
}

tasks.jar {
    enabled = false
}

	tasks.shadowJar {
	    archiveFileName.set("starx-velocity.jar")
	    exclude("META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.SF")
	    relocate("net.elytrium.limboapi.thirdparty.fastprepare", "io.github.addxiaoyi.starx.limbo.thirdparty.fastprepare")
    relocate("net.elytrium.limboapi.thirdparty.commons", "io.github.addxiaoyi.starx.limbo.thirdparty.commons")
	    relocate("net.elytrium.commons", "io.github.addxiaoyi.starx.limbo.thirdparty.commons")
	}

val verifyVelocityRuntimeJar = tasks.register("verifyVelocityRuntimeJar") {
    group = "verification"
    description = "Verifies the self-contained Velocity JAR package boundary"
    dependsOn(tasks.shadowJar)
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })

    doLast {
        val artifact = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = JarFile(artifact).use { jar ->
            jar.entries().asSequence().map { it.name }.toList()
        }
        val requiredEntries = listOf(
            "velocity-plugin.json",
            "mapping/blocks.json",
            "io/github/addxiaoyi/starx/limbo/thirdparty/commons/config/YamlConfig.class",
            "io/github/addxiaoyi/starx/limbo/thirdparty/fastprepare/PreparedPacketFactory.class"
        )
        val missing = requiredEntries.filterNot(entries::contains)
        if (missing.isNotEmpty()) {
            throw GradleException("Velocity runtime JAR is missing required entries: $missing")
        }

        val forbiddenPrefixes = listOf(
            "net/elytrium/commons/",
            "net/elytrium/fastprepare/",
            "net/elytrium/limboapi/thirdparty/",
            "it/unimi/dsi/fastutil/"
        )
        val leaked = entries.filter { entry ->
            forbiddenPrefixes.any(entry::startsWith)
        }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "Velocity runtime JAR contains non-private or core-provided classes: " +
                    leaked.take(20)
            )
        }

        val nestedJars = entries.filter { it.endsWith(".jar") }
        if (nestedJars.isNotEmpty()) {
            throw GradleException("Velocity runtime JAR contains nested JARs: $nestedJars")
        }
        if (entries.count { it == "velocity-plugin.json" } != 1) {
            throw GradleException("Velocity runtime JAR must contain exactly one plugin descriptor")
        }
    }
}

tasks.check {
    dependsOn(verifyVelocityRuntimeJar)
}

tasks.build {
    dependsOn(verifyVelocityRuntimeJar)
}
