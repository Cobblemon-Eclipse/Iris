plugins {
    id("multiloader-platform")

    id("fabric-loom") version ("1.10.1")
}

base {
    archivesName = "iris-fabric"
}

val configurationCommonModJava: Configuration = configurations.create("commonJava") {
    isCanBeResolved = true
}
val configurationCommonModJavaCompile: Configuration = configurations.create("commonJavaCompile") {
    isCanBeResolved = true
}
val configurationCommonModResources: Configuration = configurations.create("commonResources") {
    isCanBeResolved = true
}

dependencies {
    configurationCommonModJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonModJava(project(path = ":common", configuration = "commonApiJava"))
    configurationCommonModJava(project(path = ":common", configuration = "commonVendoredJava"))
    configurationCommonModJavaCompile(project(path = ":common", configuration = "commonHeadersJava"))

    configurationCommonModResources(project(path = ":common", configuration = "commonMainResources"))
    configurationCommonModResources(project(path = ":common", configuration = "commonApiResources"))
    configurationCommonModResources(project(path = ":common", configuration = "commonVendoredResources"))
}

sourceSets.apply {
    main {
        compileClasspath += configurationCommonModJava
        compileClasspath += configurationCommonModJavaCompile
        runtimeClasspath += configurationCommonModJava
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${BuildConfig.MINECRAFT_VERSION}")
    mappings(loom.layered {
        officialMojangMappings()

        if (BuildConfig.PARCHMENT_VERSION != null) {
            parchment("org.parchmentmc.data:parchment-${BuildConfig.MINECRAFT_VERSION}:${BuildConfig.PARCHMENT_VERSION}@zip")
        }
    })

    modImplementation("net.fabricmc:fabric-loader:${BuildConfig.FABRIC_LOADER_VERSION}")

    fun addEmbeddedFabricModule(name: String) {
        val module = fabricApi.module(name, "0.116.1+1.21.5")
        modImplementation(module)
        include(module)
    }

    fun addRuntimeFabricModule(name: String) {
        val module = fabricApi.module(name, "0.116.1+1.21.5")
        modImplementation(module)
    }

    fun modInclude(name : String) {
        modRuntimeOnly(name)
        include(name)
    }

    // Fabric API modules
    addEmbeddedFabricModule("fabric-api-base")
    addEmbeddedFabricModule("fabric-key-binding-api-v1")
    addRuntimeFabricModule("fabric-block-view-api-v2")
    addRuntimeFabricModule("fabric-rendering-data-attachment-v1")
    addRuntimeFabricModule("fabric-rendering-fluids-v1")
    addRuntimeFabricModule("fabric-resource-loader-v0")

    modImplementation(files(rootDir.resolve("sodium-fabric-0.7.0-snapshot+mc25w09a-local.jar")))
    modInclude("org.antlr:antlr4-runtime:4.13.1")
    modInclude("io.github.douira:glsl-transformer:2.0.1")
    modInclude("org.anarres:jcpp:1.4.14")
}

loom {
    accessWidenerPath.set(file("src/main/resources/iris-fabric.accesswidener"))

    mixin {
        defaultRefmapName.set("iris-fabric.refmap.json")
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric/Client"
            appendProjectPathToConfigName = false
            ideConfigGenerated(true)
            environmentVariable("LD_PRELOAD", "/usr/lib/librenderdoc.so")
            runDir("run")
        }
    }
}

tasks {
    jar {
        from(configurationCommonModJava)
    }

    remapJar {
        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods"))
    }

    processResources {
        from(configurationCommonModResources)
    }
}
