plugins {
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

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.papermc.io/repository/snapshots/")
}

dependencies {
    compileOnlyApi(velocityBuild606Compile)
    compileOnlyApi("net.kyori:adventure-api:4.26.1")
    compileOnlyApi("net.kyori:adventure-nbt:4.26.1")
    compileOnly("org.checkerframework:checker-qual:4.2.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly(velocityBuild606Compile)
    testRuntimeOnly(velocityBuild606Runtime)
    testRuntimeOnly("net.kyori:adventure-api:4.26.1")
    testRuntimeOnly("net.kyori:adventure-nbt:4.26.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
