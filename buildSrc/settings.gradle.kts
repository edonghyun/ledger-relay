// Build settings shared by the modules (convention plugins). Versions live in one place: gradle/libs.versions.toml at the root
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
