pluginManagement {
    repositories {
        // 国内镜像优先，官方仓库保留作回退（foojay 插件标记只在 gradle-plugin 代理上有）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // IDE/CI 环境可能通过 init.gradle 注入镜像仓库；优先使用这里声明的仓库，
    // 同时避免全局脚本注入仓库导致 Gradle 直接失败。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 国内镜像优先，官方仓库保留作回退；xposed 依赖只有官方源提供
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "WANmusic"
include(":app")
