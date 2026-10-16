pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // sherpa-onnx AAR (offizieller Weg laut k2-fsa-Demo)
    }
}
rootProject.name = "SimpleNarrator"
include(":app")
