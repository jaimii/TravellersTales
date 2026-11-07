plugins {
    kotlin("jvm") version "2.4.21" // Latest stable Kotlin (use "2.5.0-Beta2" only if you need EAP features)
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "project.kompass"
version = "1.2"
description = "The Project: Kompass module for handling custom bosses, vanilla bosses, and mobs."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
    maven("https://nexus.scarsz.me/content/groups/public/")    // DiscordSRV
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
    compileOnly("com.discordsrv:discordsrv:1.29.0")
}

    kotlin {
        jvmToolchain(25)
    }

    tasks {
        build {
            dependsOn(shadowJar)
        }

        shadowJar {
            archiveClassifier.set("")
            relocate("kotlin", "${project.group}.travellersTales.libs.kotlin")
            relocate("org.intellij.lang.annotations", "${project.group}.travellersTales.libs.ij.annotations")
            relocate("org.jetbrains.annotations", "${project.group}.travellersTales.libs.jb.annotations")
        }

        runServer {
            minecraftVersion("26.3")
            jvmArgs("-Xms2G", "-Xmx2G", "-XX:+UseG1GC")
        }

        processResources {
            filteringCharset = Charsets.UTF_8.name()
            val props = mapOf(
                "version" to project.version.toString(),
                "description" to (project.description ?: "")
            )
            inputs.properties(props) // Ensures Gradle re-runs this task when version/description changes
            filesMatching("plugin.yml") {
                expand(props)
            }
        }
    }


