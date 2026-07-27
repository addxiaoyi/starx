import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5" apply false
}

allprojects {
    group = "io.github.addxiaoyi.starx"
    version = "0.3.0"

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
        // Velocity 3.5.0-SNAPSHOT 仓库
        maven { url = uri("https://repo.papermc.io/repository/snapshots/") }
        maven { url = uri("https://repo.extendedclip.com/releases/") }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

val velocityBuild606Artifact = layout.projectDirectory.file(
    "vendor/velocity/velocity-3.5.0-SNAPSHOT-606.jar"
)
val velocityFastutilArtifact = layout.projectDirectory.file(
    "vendor/velocity/fastutil-8.5.18.jar"
)
val velocityBuild606Sha256 =
    "F763B42B951892C62ECDEE2E532A7788C9929A4468068227DAEA71D84F2B39F2"
val velocityFastutilSha256 =
    "9094AE67D01D0AD246F886F11AD557FC2E79C72CBF3FEED83E1512A8AE90A74A"
val velocityBuild606Version = "3.5.0-SNAPSHOT (git-1edab141-b606)"
val velocityBuild606CompileNamespaces = listOf(
    "com/velocitypowered",
    "com/mojang/brigadier",
    "com/google/common",
    "com/google/gson",
    "com/google/inject",
    "io/netty",
    "net/kyori",
    "org/checkerframework",
    "org/jspecify",
    "org/slf4j",
    "org/yaml/snakeyaml"
)

val verifyVelocityBuild606 by tasks.registering {
    group = "verification"
    description = "Verifies the exact Velocity build 606 compile input"
    inputs.file(velocityBuild606Artifact)

    doLast {
        val artifact = velocityBuild606Artifact.asFile
        if (!artifact.isFile) {
            throw GradleException("Missing vendored Velocity build 606: ${artifact.path}")
        }

        val actualHash = artifact.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte ->
                "%02X".format(byte.toInt() and 0xff)
            }
        }
        if (actualHash != velocityBuild606Sha256) {
            throw GradleException(
                "Velocity build 606 hash mismatch: expected=$velocityBuild606Sha256 actual=$actualHash"
            )
        }

        val actualVersion = JarFile(artifact).use { jar ->
            jar.manifest?.mainAttributes?.getValue("Implementation-Version")
        }
        if (actualVersion != velocityBuild606Version) {
            throw GradleException(
                "Velocity build 606 manifest mismatch: expected=$velocityBuild606Version " +
                    "actual=$actualVersion"
            )
        }
    }
}

val verifyVelocityFastutil by tasks.registering {
    group = "verification"
    description = "Verifies the full fastutil compile baseline used with Velocity build 606"
    inputs.file(velocityFastutilArtifact)

    doLast {
        val artifact = velocityFastutilArtifact.asFile
        if (!artifact.isFile) {
            throw GradleException("Missing vendored Velocity fastutil baseline: ${artifact.path}")
        }

        val actualHash = artifact.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte ->
                "%02X".format(byte.toInt() and 0xff)
            }
        }
        if (actualHash != velocityFastutilSha256) {
            throw GradleException(
                "Velocity fastutil hash mismatch: expected=$velocityFastutilSha256 actual=$actualHash"
            )
        }
    }
}

val prepareVelocityBuild606Compile by tasks.registering(
    org.gradle.api.tasks.bundling.Zip::class
) {
    group = "build setup"
    description = "Extracts the exact Velocity namespaces used for compilation"
    dependsOn(verifyVelocityBuild606, verifyVelocityFastutil)
    inputs.property("compileNamespaces", velocityBuild606CompileNamespaces)
    from(zipTree(velocityBuild606Artifact)) {
        include(velocityBuild606CompileNamespaces.map { "$it/**" })
    }
    from(zipTree(velocityFastutilArtifact)) {
        include("it/unimi/dsi/fastutil/**")
    }
    archiveFileName.set("velocity-build606-compile.jar")
    destinationDirectory.set(layout.buildDirectory.dir("velocity-build606"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

subprojects {
    tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(rootProject.tasks.named("prepareVelocityBuild606Compile"))
    }
    tasks.withType<Test>().configureEach {
        dependsOn(rootProject.tasks.named("prepareVelocityBuild606Compile"))
    }
}
