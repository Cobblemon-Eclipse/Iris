plugins {
    id("java")
    id("idea")
    id("net.fabricmc.fabric-loom-no-remap") version "1.14.0-alpha.20"
}

val MINECRAFT_VERSION: String by rootProject.extra
val PARCHMENT_VERSION: String? by rootProject.extra
val FABRIC_LOADER_VERSION: String by rootProject.extra
val FABRIC_API_VERSION: String by rootProject.extra
val SODIUM_DEPENDENCY_FABRIC: Any by rootProject.extra
val MOD_VERSION: String by rootProject.extra

repositories {
    mavenLocal()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

base {
    archivesName.set("iris-fabric")
}
val configurationCommonModJava: Configuration = configurations.create("commonJava") {
    isCanBeResolved = true
}
val configurationHeadersModJava: Configuration = configurations.create("headersJava") {
    isCanBeResolved = true
}
val configurationCommonModResources: Configuration = configurations.create("commonResources") {
    isCanBeResolved = true
}
dependencies {
    minecraft("com.mojang:minecraft:${MINECRAFT_VERSION}")

    implementation("net.fabricmc:fabric-loader:$FABRIC_LOADER_VERSION")

    fun addRuntimeFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        runtimeOnly(module)
    }

    fun addEmbeddedFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        implementation(module)
        include(module)
    }

    fun implementAndInclude(name: String) {
        implementation(name)
        include(name)
    }

    // Fabric API modules
    addEmbeddedFabricModule("fabric-api-base")
    addEmbeddedFabricModule("fabric-key-binding-api-v1")
    addRuntimeFabricModule("fabric-block-view-api-v2")
    addRuntimeFabricModule("fabric-rendering-fluids-v1")
    addRuntimeFabricModule("fabric-resource-loader-v0")
    addRuntimeFabricModule("fabric-lifecycle-events-v1")
    addRuntimeFabricModule("fabric-renderer-api-v1")

    implementation(SODIUM_DEPENDENCY_FABRIC)
    implementAndInclude("org.antlr:antlr4-runtime:4.13.1")
    implementAndInclude("io.github.douira:glsl-transformer:3.0.0-pre3")
    implementAndInclude("org.anarres:jcpp:1.4.14")

    configurationCommonModJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonModJava(project(path = ":common", configuration = "commonApiJava"))
    configurationCommonModJava(project(path = ":common", configuration = "commonVendoredJava"))
    configurationHeadersModJava(project(path = ":common", configuration = "commonHeadersJava"))

    configurationCommonModResources(project(path = ":common", configuration = "commonMainResources"))
    configurationCommonModResources(project(path = ":common", configuration = "commonApiResources"))
    configurationCommonModResources(project(path = ":common", configuration = "commonVendoredResources"))

    compileOnly(files(rootDir.resolve("DHApi.jar")))
}
sourceSets.apply {
    main {
        compileClasspath += configurationCommonModJava
        compileClasspath += configurationHeadersModJava
        runtimeClasspath += configurationCommonModJava
    }
}
tasks.named("compileTestJava").configure {
    enabled = false
}

tasks.named("test").configure {
    enabled = false
}

loom {
    if (project(":common").file("src/main/resources/iris.accesswidener").exists())
        accessWidenerPath.set(project(":common").file("src/main/resources/iris.accesswidener"))

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
           // vmArgs("-Dmixin.debug.export=true")
           // vmArg("-XX:+AllowEnhancedClassRedefinition")
        }
        create("clientWithRenderdoc") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
            environmentVariable("LD_PRELOAD", "/usr/lib/librenderdoc.so")
            vmArgs("-DMC_DEBUG_ENABLED=true", "-DMC_DEBUG_DUMP_TEXTURE_ATLAS=true")
            programArgs("--renderDebugLabels")
        }
    }
}

tasks {
    jar {
        from(configurationCommonModJava)
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }

    jar {
        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods"))
    }

    processResources {
        from(configurationCommonModResources)
    }
}
