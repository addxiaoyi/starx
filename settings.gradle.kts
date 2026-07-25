pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "starx"

include("starx-plugins:starx-api")
include("starx-plugins:starx-extension-runtime")
include("starx-plugins:starx-common")
include("starx-plugins:starx-limbo-api")
include("starx-plugins:starx-standalone-limbo")
include("starx-plugins:starx-velocity")
include("starx-plugins:starx-server")
include("starx-plugins:starx-universal")
include("starx-plugins:starx-extension-example")
