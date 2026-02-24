import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.PAPERCLIP_CONFIG

plugins {
    java
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("io.papermc.paperweight.patcher") version "1.5.10"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        content {
            onlyForConfigurations(PAPERCLIP_CONFIG)
        }
    }
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:0.8.11:fat")
    decompiler("net.minecraftforge:forgeflower:2.0.627.2")
    paperclip("io.papermc:paperclip:3.0.3")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

subprojects {
    tasks {
        withType<JavaCompile>().configureEach {
            options.encoding = Charsets.UTF_8.name()
            options.release.set(17)
        }
        withType<Javadoc> {
            options.encoding = Charsets.UTF_8.name()
        }
        withType<ProcessResources> {
            filteringCharset = Charsets.UTF_8.name()
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://libraries.minecraft.net/")
        maven("https://jitpack.io")
    }
}

paperweight {
    serverProject.set(project(":supervertex-server"))

    remapRepo.set("https://maven.fabricmc.net/")
    decompileRepo.set("https://files.minecraftforge.net/maven/")

    useStandardUpstream("plazma") {
        url.set(github("PlazmaMC", "PlazmaBukkit"))
        ref.set(providers.gradleProperty("plazmaCommit"))

        withStandardPatcher {
            apiSourceDirPath.set("Plazma-API")
            serverSourceDirPath.set("Plazma-Server")

            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("SuperVertex-API"))

            serverPatchDir.set(layout.projectDirectory.dir("patches/server"))
            serverOutputDir.set(layout.projectDirectory.dir("SuperVertex-Server"))
        }
    }
}
