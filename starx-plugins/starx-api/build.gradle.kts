import java.util.jar.JarFile
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
}

version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Javadoc> {
    isFailOnError = true
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        addBooleanOption("Werror", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("starxApi") {
            from(components["java"])
            artifactId = "starx-api"
            pom {
                name.set("StarX Extension API")
                description.set("Stable third-party extension API for StarX on Velocity, Paper, and Folia")
            }
        }
    }
}

val verifyPublicApiJar = tasks.register("verifyPublicApiJar") {
    group = "verification"
    description = "Verifies that the published StarX API JAR contains contracts only"
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        val entries = JarFile(artifact).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }
        val required = listOf(
            "io/github/addxiaoyi/starx/api/bridge/BridgeProtocol.class",
            "io/github/addxiaoyi/starx/api/extension/StarxService.class",
            "io/github/addxiaoyi/starx/api/extension/StarxServiceProvider.class",
            "io/github/addxiaoyi/starx/api/extension/StarxExtension.class"
        )
        val missing = required.filterNot(entries::contains)
        if (missing.isNotEmpty()) throw GradleException("Public StarX API JAR is missing contracts: $missing")
        val forbidden = entries.filter { entry ->
            entry.startsWith("io/github/addxiaoyi/starx/api/extension/internal/") ||
                entry.startsWith("io/github/addxiaoyi/starx/runtime/") ||
                entry.endsWith("DefaultStarxService.class")
        }
        if (forbidden.isNotEmpty()) throw GradleException("Public StarX API JAR contains runtime implementation: $forbidden")
        logger.lifecycle("STARX_PUBLIC_API_JAR_VERIFICATION=PASS")
    }
}

tasks.check { dependsOn(verifyPublicApiJar) }
