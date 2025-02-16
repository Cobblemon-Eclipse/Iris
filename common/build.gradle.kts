plugins {
    id("multiloader-base")
    id("java-library")

    id("fabric-loom") version ("1.10.1")
    id("com.github.gmazzo.buildconfig") version "5.3.5"
}

base {
    archivesName = "iris-common"
}

sourceSets.create("desktop")

buildConfig {
    className("BuildConfig")   // forces the class name. Defaults to 'BuildConfig'
    packageName("net.irisshaders.iris")  // forces the package. Defaults to '${project.group}'
    useJavaOutput()

    // TODO hook this up
    buildConfigField("IS_SHARED_BETA", false)
    buildConfigField("ACTIVATE_RENDERDOC", false)
    buildConfigField("BETA_TAG", "")
    buildConfigField("BETA_VERSION", 0)

    sourceSets.getByName("desktop") {
        buildConfigField("IS_SHARED_BETA", false)
    }
}

sourceSets {
    val main = getByName("main")
    val api = create("api")
    val desktop = getByName("desktop")
    val vendored = create("vendored")
    val headers = create("headers")

    api.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    vendored.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    headers.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    desktop.apply {

    }

    main.apply {
        java {
            compileClasspath += api.output
            compileClasspath += vendored.output
            compileClasspath += headers.output
        }
    }
}

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = BuildConfig.MINECRAFT_VERSION)
    mappings(loom.layered {
        officialMojangMappings()

        if (BuildConfig.PARCHMENT_VERSION != null) {
            parchment("org.parchmentmc.data:parchment-${BuildConfig.MINECRAFT_VERSION}:${BuildConfig.PARCHMENT_VERSION}@zip")
        }
    })

    compileOnly("io.github.llamalad7:mixinextras-common:0.4.1")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.4.1")

    compileOnly("net.fabricmc:sponge-mixin:0.13.2+mixin.0.8.5")
    compileOnly("net.fabricmc:fabric-loader:${BuildConfig.FABRIC_LOADER_VERSION}")

    fun addDependentFabricModule(name: String) {
        modCompileOnly(fabricApi.module(name, BuildConfig.FABRIC_API_VERSION))
    }

    addDependentFabricModule("fabric-api-base")
    addDependentFabricModule("fabric-block-view-api-v2")
    addDependentFabricModule("fabric-rendering-data-attachment-v1")
    compileOnly("maven.modrinth:distanthorizonsapi:3.0.0")

    modImplementation(files(rootDir.resolve("sodium-fabric-0.7.0-snapshot+mc25w07a-local.jar")))
    modCompileOnly("org.antlr:antlr4-runtime:4.13.1")
    modCompileOnly("io.github.douira:glsl-transformer:2.0.1")
    modCompileOnly("org.anarres:jcpp:1.4.14")
}

gradle.projectsEvaluated {
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xmaxerrs");
        options.compilerArgs.add("400");
    }
}

loom {
    accessWidenerPath = file("src/main/resources/iris.accesswidener")

    mixin {
        useLegacyMixinAp = false
    }
}

fun exportSourceSetJava(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Java") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<JavaCompile>(sourceSet.compileJavaTaskName)
    artifacts.add(configuration.name, compileTask.destinationDirectory) {
        builtBy(compileTask)
    }
}

fun exportSourceSetResources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Resources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<ProcessResources>(sourceSet.processResourcesTaskName)
    compileTask.apply {
        exclude("**/README.txt")
        exclude("/*.accesswidener")
    }

    artifacts.add(configuration.name, compileTask.destinationDir) {
        builtBy(compileTask)
    }
}

// Exports the compiled output of the source set to the named configuration.
fun exportSourceSet(name: String, sourceSet: SourceSet) {
    exportSourceSetJava(name, sourceSet)
    exportSourceSetResources(name, sourceSet)
}

exportSourceSet("commonMain", sourceSets["main"])
exportSourceSet("commonApi", sourceSets["api"])
exportSourceSet("commonVendored", sourceSets["vendored"])
exportSourceSet("commonHeaders", sourceSets["headers"])
exportSourceSet("commonDesktop", sourceSets["desktop"])

tasks.jar { enabled = false }
tasks.remapJar { enabled = false }
