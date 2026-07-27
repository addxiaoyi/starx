import java.security.MessageDigest
import java.util.jar.JarFile
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    base
}

val velocityJar = project(":starx-plugins:starx-velocity")
    .layout.buildDirectory.file("libs/starx-velocity.jar")
val serverJar = project(":starx-plugins:starx-server")
    .layout.buildDirectory.file("libs/starx-server.jar")
val universalVersion = project.version.toString()

val universalAssemblySchema = 5
val canonicalServerApiEntries = listOf(
    "io/github/addxiaoyi/starx/api/bridge/BridgeMessage.class",
    "io/github/addxiaoyi/starx/api/bridge/BridgeProtocol.class",
    "io/github/addxiaoyi/starx/api/bridge/PlatformKind.class"
)
val canonicalServerApiPrefixes = listOf(
    "io/github/addxiaoyi/starx/api/compat/",
    "io/github/addxiaoyi/starx/api/extension/",
    "io/github/addxiaoyi/starx/runtime/extension/",
    "io/github/addxiaoyi/starx/website/"
)

val universalJar = tasks.register<Jar>("universalJar") {
    group = "build"
    description = "Builds one plugin JAR accepted by Velocity, Paper, and Folia"
    dependsOn(
        ":starx-plugins:starx-velocity:shadowJar",
        ":starx-plugins:starx-server:shadowJar"
    )
    inputs.files(velocityJar, serverJar)
    inputs.property("universalAssemblySchema", universalAssemblySchema)
    inputs.property("canonicalServerApiEntries", canonicalServerApiEntries)
    inputs.property("canonicalServerApiPrefixes", canonicalServerApiPrefixes)
    archiveFileName.set("starx-universal.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    // The backend artifact owns the canonical shared API and extension runtime copies.
    from({ zipTree(serverJar.get().asFile) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "META-INF/*.SF",
            "META-INF/versions/**/module-info.class"
        )
    }

    // Velocity embeds the same API and extension runtime. Exclude those duplicate copies.
    from({ zipTree(velocityJar.get().asFile) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "META-INF/*.SF",
            "META-INF/versions/**/module-info.class",
            *canonicalServerApiEntries.toTypedArray(),
            "io/github/addxiaoyi/starx/api/compat/**",
            "io/github/addxiaoyi/starx/api/extension/**",
            "io/github/addxiaoyi/starx/runtime/extension/**",
            "io/github/addxiaoyi/starx/website/**"
        )
    }

    manifest {
        attributes(
            "Implementation-Title" to "StarX Universal Plugin",
            "Implementation-Version" to universalVersion,
            "StarX-Platforms" to "Velocity,Paper,Folia"
        )
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

val verifyUniversalJar = tasks.register("verifyUniversalJar") {
    group = "verification"
    description = "Verifies the universal plugin package and cross-platform class boundary"
    dependsOn(universalJar)
    inputs.files(velocityJar, serverJar, universalJar.flatMap { it.archiveFile })

    doLast {
        val velocityArtifact = velocityJar.get().asFile
        val serverArtifact = serverJar.get().asFile
        val universalArtifact = universalJar.get().archiveFile.get().asFile

        fun readEntries(file: java.io.File): Map<String, ByteArray> = JarFile(file).use { jar ->
            jar.entries().asSequence()
                .filterNot { it.isDirectory }
                .associate { entry -> entry.name to jar.getInputStream(entry).use { it.readBytes() } }
        }

        val velocityEntries = readEntries(velocityArtifact)
        val serverEntries = readEntries(serverArtifact)
        val universalEntries = readEntries(universalArtifact)
        val sourceOverlap = velocityEntries.keys.intersect(serverEntries.keys)
        val expectedOverlap = setOf(
            "META-INF/MANIFEST.MF",
            "META-INF/versions/9/module-info.class"
        ) + canonicalServerApiEntries + sourceOverlap.filter { entry ->
            canonicalServerApiPrefixes.any(entry::startsWith)
        }
        val unexpectedOverlap = sourceOverlap - expectedOverlap
        if (unexpectedOverlap.isNotEmpty()) {
            throw GradleException("Universal source JARs gained unexpected duplicate entries: $unexpectedOverlap")
        }

        val required = listOf(
            "velocity-plugin.json",
            "plugin.yml",
            "io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.class",
            "io/github/addxiaoyi/starx/server/StarxServerPlugin.class",
            "io/github/addxiaoyi/starx/api/bridge/BridgeProtocol.class",
            "io/github/addxiaoyi/starx/api/compat/CompatibilityReport.class",
            "io/github/addxiaoyi/starx/api/extension/StarxService.class",
            "io/github/addxiaoyi/starx/api/extension/StarxServiceProvider.class",
            "io/github/addxiaoyi/starx/runtime/extension/DefaultStarxService.class",
            "io/github/addxiaoyi/starx/website/WebsiteSyncRuntime.class"
        )
        val missing = required.filterNot(universalEntries::containsKey)
        if (missing.isNotEmpty()) {
            throw GradleException("Universal JAR is missing required entries: $missing")
        }

        val forbiddenPrefixes = listOf(
            "com/velocitypowered/",
            "org/bukkit/",
            "io/papermc/paper/"
        )
        val leakedPlatformApi = universalEntries.keys.filter { entry ->
            forbiddenPrefixes.any(entry::startsWith)
        }
        if (leakedPlatformApi.isNotEmpty()) {
            throw GradleException("Universal JAR embeds platform API classes: ${leakedPlatformApi.take(20)}")
        }

        val forbiddenEntries = universalEntries.keys.filter { entry ->
            entry.endsWith("/module-info.class") ||
                entry == "module-info.class" ||
                entry.endsWith(".jar") ||
                entry.matches(Regex("META-INF/.*\\.(RSA|DSA|SF)"))
        }
        if (forbiddenEntries.isNotEmpty()) {
            throw GradleException("Universal JAR contains forbidden package entries: $forbiddenEntries")
        }

        val velocityDescriptor = universalEntries.getValue("velocity-plugin.json").toString(Charsets.UTF_8)
        val serverDescriptor = universalEntries.getValue("plugin.yml").toString(Charsets.UTF_8)
        if (!velocityDescriptor.contains("io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin")) {
            throw GradleException("Velocity descriptor does not select the Velocity entrypoint")
        }
        if (!serverDescriptor.contains("io.github.addxiaoyi.starx.server.StarxServerPlugin")) {
            throw GradleException("Paper descriptor does not select the backend entrypoint")
        }
        if (!serverDescriptor.contains("folia-supported: true")) {
            throw GradleException("Universal backend descriptor does not advertise Folia support")
        }
        val expectedVersion = universalVersion
        if (!velocityDescriptor.contains("\"version\": \"$expectedVersion\"")) {
            throw GradleException("Velocity descriptor version does not match $expectedVersion")
        }
        if (!serverDescriptor.contains("version: '$expectedVersion'")) {
            throw GradleException("Paper/Folia descriptor version does not match $expectedVersion")
        }
        if (!serverDescriptor.contains("usage: /sx [邮箱|验证|关闭|重置]")) {
            throw GradleException("Paper/Folia descriptor is not valid UTF-8 or lost the /sx usage text")
        }

        val canonicalChecks = listOf(
            "io/github/addxiaoyi/starx/api/bridge/BridgeProtocol.class",
            "io/github/addxiaoyi/starx/api/compat/CompatibilityReport.class",
            "io/github/addxiaoyi/starx/api/extension/StarxService.class",
            "io/github/addxiaoyi/starx/runtime/extension/DefaultStarxService.class"
        )
        for (apiEntry in canonicalChecks) {
            val universalApiHash = sha256(universalEntries.getValue(apiEntry))
            val serverApiHash = sha256(serverEntries.getValue(apiEntry))
            if (universalApiHash != serverApiHash) {
                throw GradleException(
                    "Universal JAR did not retain canonical backend API entry: $apiEntry")
            }
        }

        val names = JarFile(universalArtifact).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it != 1 }
        if (duplicates.isNotEmpty()) {
            throw GradleException("Universal JAR contains duplicate ZIP entries: $duplicates")
        }

        logger.lifecycle("STARX_UNIVERSAL_JAR=${universalArtifact.absolutePath}")
        logger.lifecycle("STARX_UNIVERSAL_SHA256=${sha256(universalArtifact.readBytes())}")
        logger.lifecycle("STARX_UNIVERSAL_VERIFICATION=PASS")
    }
}

tasks.named("assemble") {
    dependsOn(universalJar)
}

tasks.named("check") {
    dependsOn(verifyUniversalJar)
}
