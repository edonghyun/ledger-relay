// 모듈들이 같이 쓰는 빌드 설정(convention plugin). 버전은 루트의 gradle/libs.versions.toml 하나에 둔다
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
