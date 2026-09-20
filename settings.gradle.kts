pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "starx"

include("starx-plugins:starx-api")
include("starx-plugins:starx-extension-runtime")
include("starx-plugins:starx-common")
include("starx-plugins:starx-website-sync")
include("starx-plugins:starx-limbo-api")
include("starx-plugins:starx-standalone-limbo")
include("starx-plugins:starx-velocity")
include("starx-plugins:starx-server")
include("starx-plugins:starx-universal")
include("starx-plugins:starx-extension-example")
