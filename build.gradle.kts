import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "io.github.addxiaoyi.starx"
    version = "0.5.2"

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

val verifyVelocityBuild606 = tasks.register("verifyVelocityBuild606") {
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

val verifyVelocityFastutil = tasks.register("verifyVelocityFastutil") {
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

val prepareVelocityBuild606Compile = tasks.register<org.gradle.api.tasks.bundling.Zip>(
    "prepareVelocityBuild606Compile"
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

val releaseVersion = version.toString()
val releaseReadme = layout.projectDirectory.file("README.md")
val releaseChangelog = layout.projectDirectory.file("CHANGELOG.md")
val releaseNotes = layout.projectDirectory.file("docs/releases/$releaseVersion.md")
val extensionCompatibilityPolicy = layout.projectDirectory.file(
    "docs/EXTENSION_COMPATIBILITY_POLICY.md"
)
val releaseWorkflow = layout.projectDirectory.file(".github/workflows/release.yml")
val requiredReadmeDocumentation = listOf(
    "docs/STARX_PLATFORMS.md",
    "docs/COMPATIBILITY.md",
    "docs/UWORLD_CONFIGURATION.md",
    "docs/UWORLD_ACCEPTANCE.md",
    "docs/UWORLD_ENVIRONMENT.md",
    "starx-plugins/starx-universal/README.md"
).map(layout.projectDirectory::file)

val verifyReleaseMetadata = tasks.register("verifyReleaseMetadata") {
    group = "verification"
    description = "Verifies release documentation and assets match the project version"
    inputs.files(
        releaseReadme,
        releaseChangelog,
        releaseNotes,
        extensionCompatibilityPolicy,
        releaseWorkflow,
        requiredReadmeDocumentation
    )
    inputs.property("releaseVersion", releaseVersion)

    doLast {
        fun requireFile(file: java.io.File, label: String): String {
            if (!file.isFile) {
                throw GradleException("Missing $label: ${file.path}")
            }
            return file.readText(Charsets.UTF_8)
        }

        fun requireMarkers(file: java.io.File, label: String, markers: List<String>) {
            val content = requireFile(file, label)
            val missing = markers.filterNot(content::contains)
            if (missing.isNotEmpty()) {
                throw GradleException("$label is missing release markers: $missing")
            }
        }

        requireMarkers(
            releaseReadme.asFile,
            "README.md",
            listOf(
                "当前插件版本：**$releaseVersion**",
                "starx-universal-$releaseVersion.jar",
                "release-manifest.json",
                "AUTOMATED_VERIFIED"
            )
        )
        requireMarkers(
            releaseChangelog.asFile,
            "CHANGELOG.md",
            listOf("## [$releaseVersion]")
        )
        requireMarkers(
            releaseNotes.asFile,
            "release notes",
            listOf("# StarX $releaseVersion", "starx-universal-$releaseVersion.jar")
        )
        requireMarkers(
            extensionCompatibilityPolicy.asFile,
            "extension compatibility policy",
            listOf("插件实现当前为 `$releaseVersion`", "公共扩展 API")
        )
        requireMarkers(
            releaseWorkflow.asFile,
            "release workflow",
            listOf("release-manifest.json", "docs/releases/", "SHA256SUMS")
        )
        for (documentation in requiredReadmeDocumentation) {
            requireFile(documentation.asFile, "README documentation target")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyReleaseMetadata)
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
