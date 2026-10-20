// CI (GitHub Actions) 用官方仓库；本地 Termux 用 aliyun 镜像优先
val isCi = !System.getenv("CI").isNullOrEmpty()

pluginManagement {
    repositories {
        if (isCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/jcenter")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCi) {
            google()
            mavenCentral()
            maven("https://jitpack.io")  // For EPUB library
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
            maven("https://jitpack.io")  // For EPUB library
        }
    }
}

rootProject.name = "AudiobookApp"
include(":app")
