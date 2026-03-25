plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
    `maven-publish`
}

group = "samaritan"
version = "1.0"

base {
    archivesName.set("samaritan-fabric-client")
}

val minecraftVersion = "1.21.11"
val yarnMappings = "1.21.11+build.4"
val loaderVersion = "0.18.3"
val fabricApiVersion = "0.141.3+1.21.11"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
    options.release.set(21)
}
