plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `maven-publish`
}

group = "samaritan"
version = "1.0"

base {
    archivesName.set("samaritan-fabric-client")
}

val minecraftVersion = "26.1.2"
val loaderVersion = "0.19.3"
val fabricApiVersion = "0.153.0+26.1.2"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")

    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
