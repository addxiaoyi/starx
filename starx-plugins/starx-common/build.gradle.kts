plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src/main/java"))
        }
        resources {
            setSrcDirs(listOf("src/main/resources"))
        }
    }
}

dependencies {
    api(project(":starx-plugins:starx-api"))
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Runtime dependencies that were bundled
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.yaml:snakeyaml:2.2")
    // HikariCP 和 JDBC 驱动 - 需要被 shadow 到最终 JAR
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    // Note: OTP (TOTP) 和 BCrypt 源码已直接复制到项目中

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
