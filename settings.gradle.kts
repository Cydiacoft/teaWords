// 本机无法直连 repo.maven.apache.org / github.com（SSL 被拦、连接超时），
// 因此在国内镜像上补一份只读来源；Google 仓库直连可用，保持优先。
// 若网络环境可以直连中央仓库，删除下面的 aliyun 镜像即可，其余配置不受影响。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "teaWords"
include(":app")
