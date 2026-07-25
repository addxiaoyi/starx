import java.security.MessageDigest

plugins {
    `maven-publish`
    `java-library`
}

group = "io.github.addxiaoyi.starx"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val velocityBuild606Compile = files(
    rootProject.tasks.named("prepareVelocityBuild606Compile")
)
val velocityBuild606Runtime = files(
    rootProject.layout.projectDirectory.file("vendor/velocity/velocity-3.5.0-SNAPSHOT-606.jar")
)
val velocityFastutilRuntime = files(
    rootProject.layout.projectDirectory.file("vendor/velocity/fastutil-8.5.18.jar")
)
val limboVendorArtifact = layout.projectDirectory.file("vendor/limboapi-1.1.27-SNAPSHOT.jar")
val limboVendorSha256 =
    "18AC6287D413234C4FC317267A6D5DBF978ADAE8BF3F098A1248966BF2C32CE9"
val limboVendorNamespaces = listOf(
    "net/elytrium/limboapi/thirdparty/commons",
    "net/elytrium/limboapi/thirdparty/fastprepare",
    "net/elytrium/commons/utils/reflection"
)

val verifyLimboVendor by tasks.registering {
    group = "verification"
    description = "Verifies the pinned LimboAPI vendor artifact"
    inputs.file(limboVendorArtifact)

    doLast {
        val artifact = limboVendorArtifact.asFile
        if (!artifact.isFile) {
            throw GradleException("Missing vendored LimboAPI artifact: ${artifact.path}")
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
        if (actualHash != limboVendorSha256) {
            throw GradleException(
                "LimboAPI vendor hash mismatch: expected=$limboVendorSha256 actual=$actualHash"
            )
        }
    }
}

val prepareLimboVendorRuntime by tasks.registering(
    org.gradle.api.tasks.bundling.Zip::class
) {
    group = "build setup"
    description = "Extracts the pinned Elytrium libraries used by embedded Uworld"
    dependsOn(verifyLimboVendor)
    inputs.property("runtimeNamespaces", limboVendorNamespaces)
    from(zipTree(limboVendorArtifact)) {
        include(limboVendorNamespaces.map { "$it/**" })
    }
    archiveFileName.set("limbo-vendor-runtime.jar")
    destinationDirectory.set(layout.buildDirectory.dir("vendor-runtime"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    api(project(":starx-plugins:starx-limbo-api"))

    implementation(files(prepareLimboVendorRuntime))

    compileOnly(velocityBuild606Compile)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly(velocityBuild606Compile)
    testRuntimeOnly(velocityBuild606Runtime)
    testRuntimeOnly(velocityFastutilRuntime)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    from(zipTree(layout.projectDirectory.file("vendor/limboapi-1.1.27-SNAPSHOT.jar"))) {
        include("mapping/**")
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("StarX Standalone Limbo")
                description.set("Embedded StarX Uworld runtime library")
            }
        }
    }
}
