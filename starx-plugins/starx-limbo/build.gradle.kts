plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly(project(":starx-plugins:starx-api"))
    compileOnly(project(":starx-plugins:starx-common"))
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("io.netty:netty-buffer:4.1.118.Final")
    compileOnly("net.kyori:adventure-nbt:4.26.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
