plugins {
    id("dev.architectury.loom").version("1.9-SNAPSHOT")
    // id("com.hypherionmc.modutils.modpublisher").version("2.+")
    id("maven-publish")
}


val minecraft_version: String by project
val mappings_version: String by project
val forge_version: String by project
val mafglib_version: String by project
val forgematica_version: String by project

val archives_base_name: String by project
val mod_version: String by project

val curseforge_id: String by project
val modrinth_id: String by project

base.archivesName = archives_base_name
version = "${mod_version}+mc${minecraft_version}"

loom {
    forge {
        mixinConfigs("litematica-printer.mixins.json", "litematica-printer-implementation.mixins.json")
    }
}

java {
    withSourcesJar()

    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://masa.dy.fi/maven")
    //maven("https://www.cursemaven.com")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://jitpack.io")
    maven("https://maven.neoforged.net/releases")
    maven("https://api.modrinth.com/maven")
    maven {
        url = uri("https://maven2.bai.lol")
        content {
            includeGroup("lol.bai")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraft_version}")
    mappings("net.fabricmc:yarn:${mappings_version}:v2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    forge("net.minecraftforge:forge:${forge_version}")

    modImplementation("maven.modrinth:mafglib:${mafglib_version}")
    modImplementation("maven.modrinth:forgematica:${forgematica_version}")
}

tasks.withType<ProcessResources> {
    inputs.property("version", version)

    filesMatching("META-INF/mods.toml") {
        expand(mapOf("version" to version))
    }
}