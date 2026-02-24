plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.14"
}

group = "dev.supervertex"
version = "1.21.4-R0.1-SNAPSHOT"
description = "SuperVertex - High performance Minecraft server software based on PlazmaMC"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")
    decompiler("org.vineflower:vineflower:1.10.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

paperweight {
    upstreamData.set(providers.provider {
        io.papermc.paperweight.patcher.upstream.PatchTaskConfig.UpstreamData(
            upstream = "plazma",
            upstreamBranch = "main",
            serverProject = "super-vertex-server",
            apiProject = "super-vertex-api"
        )
    })
}
