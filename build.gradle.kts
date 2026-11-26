@file:Suppress("AvoidDuplicateDependencies")

import net.bichal.gradle.BichalModExtension
import net.neoforged.moddevgradle.dsl.NeoForgeExtension

// bichal.mod: bichal.neoforge-mod on the NeoForge nodes ("1.21.1"),
// bichal.fabric-mod on the Fabric ones ("1.21.1-fabric").
plugins {
    id("bichal.mod")
    `maven-publish`
    idea
}

// Celeris (dependency and Maven repositories), natives, neoforge.mods.toml /
// fabric.mod.json expansion, legal files and buildAndCollect come from bichal.mod:
// [depends_on.celeris], [natives.tessera_bridge] and [legal] in the TOML.

val modProps = extensions.getByType(BichalModExtension::class.java).props
val defaultQualityPreset: Long = modProps.req(project, "compression.default_quality_preset").toLong()
val mainSourceSet = sourceSets.main.get()
val generatedResources: File = rootProject.file("src/generated/resources")
// -PselftestMods=<jar>[,<jar>] adds mods to a Fabric run (e.g. a stand-in for Kerria).
val selftestMods = findProperty("selftestMods") as String?
// -PselftestFormats=astc|bc limits Tessera to one family of block formats in the self-test run.
val selftestFormats = findProperty("selftestFormats") as String?
// -PselftestCompat=true runs the self-test without the native bridge (the pure-Java encoders).
val selftestCompat = (findProperty("selftestCompat") as String?) == "true"
// -PselftestDump=<dir> writes every compressed atlas (original and decoded, raw RGBA) there.
val selftestDump = findProperty("selftestDump") as String?
// -PselftestWorld=<commands file> (1.21.1): builds a showcase world and waits for a screenshot (see TesseraSelfTest).
val selftestWorldCommands = findProperty("selftestWorld") as String?
val selftestDir = rootProject.file("versions/${modProps.currentVersion}/run-selftest")

pluginManager.withPlugin("net.neoforged.moddev") { configure<NeoForgeExtension> {
    runs {
        all {
            val runDir = rootProject.file("versions/${modProps.currentVersion}/run")
            if (!runDir.exists()) runDir.mkdirs()

            sourceSet = mainSourceSet
            gameDirectory = runDir

            systemProperty("tessera.compression.defaultQualityPreset", defaultQualityPreset.toString())
        }

        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modProps.modId)
        }

        // Headless client check of the compressed atlases: TesseraSelfTest.
        register("selftest") {
            client()
            gameDirectory = selftestDir
            systemProperty("tessera.selftest", "true")
            if (selftestWorldCommands != null) systemProperty("tessera.selftest.world", selftestWorldCommands)
            systemProperty("tessera.selftest.version", modProps.currentVersion)
            if (selftestFormats != null) systemProperty("tessera.formats", selftestFormats)
            if (selftestCompat) systemProperty("tessera.compatMode", "true")
            if (selftestDump != null) systemProperty("tessera.selftest.dump", selftestDump)
            jvmArgument("-Xmx2G")
        }

        // Runs DataGenerators (en_us / es_es lang, incl. config-screen keys).
        // Without this run the providers never execute, so the lang JSON is
        // never produced and NeoForge reports every config key as untranslated.
        register("data") {
            data()
            programArguments.addAll(
                "--mod", modProps.modId,
                "--all",
                "--output", generatedResources.absolutePath,
                "--existing", rootProject.file("src/main/resources").absolutePath
            )
        }
    }
} }

// NeoForge makes some SpriteContents members public; Fabric widens them. 1.21.x is
// obfuscated (access widener namespace "named"), 26.x is not ("official"); the right file
// ships in the jar as tessera.accesswidener, the other is left out.
val accessWidener = when {
    modProps.mcVersion == "1.21.11" -> "tessera-named-1.21.11.accesswidener" // + the 1.21.11 animation state
    modProps.mcVersion.startsWith("1.") -> "tessera-named.accesswidener"
    else -> "tessera-official.accesswidener"
}
if (modProps.isFabric) {
    // The Mod Menu entrypoint (TesseraModMenu) compiles against Mod Menu's API only; Mod Menu is
    // neither bundled nor required (it calls the entrypoint when installed).
    repositories {
        maven("https://maven.terraformersmc.com/releases/") {
            name = "Terraformers"
            content { includeGroup("com.terraformersmc") }
        }
    }
    val modMenu = "com.terraformersmc:modmenu:${modProps.req(project, "modmenu_version")}"
    val unobfuscated = !modProps.mcVersion.startsWith("1.")  // 26.x ships with Mojang names
    dependencies {
        add(if (unobfuscated) "compileOnly" else "modCompileOnly", modMenu) { isTransitive = false }
    }
    extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
        accessWidenerPath.set(rootProject.file("src/main/resources/$accessWidener"))
        runs {
            // Headless client check of the compressed atlases: TesseraSelfTest.
            create("selftest") {
                client()
                runDirectory.set(layout.projectDirectory.dir("run-selftest"))
                jvmArguments.add("-Dtessera.selftest=true")
                if (selftestWorldCommands != null) jvmArguments.add("-Dtessera.selftest.world=$selftestWorldCommands")
                if ((findProperty("selftestScreen") as String?) == "true") jvmArguments.add("-Dtessera.selftest.screen=true")
                if (selftestFormats != null) jvmArguments.add("-Dtessera.formats=$selftestFormats")
                if (selftestCompat) jvmArguments.add("-Dtessera.compatMode=true")
                if (selftestDump != null) jvmArguments.add("-Dtessera.selftest.dump=$selftestDump")
                if (selftestMods != null) jvmArguments.add("-Dfabric.addMods=$selftestMods")
                jvmArguments.add("-Dtessera.selftest.version=${modProps.currentVersion}")
                jvmArguments.add("-Xmx2G")
            }
        }
    }
    // The same self-test on the built jar in a production client: intermediary names on
    // 1.21.x, as players run it (the dev runs above use Mojang names and cannot catch a
    // lookup by name that only works there).
    tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("selftestProd") {
        group = "verification"
        description = "Runs TesseraSelfTest with the remapped jar in a production Fabric client."
        mods.from(tasks.named("remapJar"))
        mods.from(configurations.detachedConfiguration(
            *configurations.getByName("modImplementation").dependencies.map { it.copy() }.toTypedArray()
        ).apply { isTransitive = false })
        if (selftestMods != null) mods.from(selftestMods.split(','))
        runDir.set(rootProject.file("versions/${modProps.currentVersion}/run-selftest-prod"))
        jvmArgs.addAll("-Dtessera.selftest=true", "-Dtessera.selftest.version=${modProps.currentVersion}", "-Xmx2G")
        if (selftestFormats != null) jvmArgs.add("-Dtessera.formats=$selftestFormats")
        if (selftestCompat) jvmArgs.add("-Dtessera.compatMode=true")
    }
}
tasks.named<ProcessResources>("processResources") {
    // Locals only: the action is stored in the configuration cache and must not capture this script.
    val shipped = if (modProps.isFabric) accessWidener else ""
    filesMatching("tessera-*.accesswidener") {
        if (name == shipped) name = "tessera.accesswidener" else exclude()
    }
}

sourceSets.main {
    resources {
        // Root-relative: this script runs per Stonecutter version project
        // (versions/<mc>/), where a relative path would point at an empty dir.
        srcDir(generatedResources)
    }
}

// The headless client self-test (runSelftest: ModDevGradle's run on NeoForge, Loom's on Fabric).
run {
    // A fresh game directory for every check: no saved options (accessibility
    // onboarding off, quiet), no stale report.
    val prepareSelftest = tasks.register("prepareSelftest") {
        val dir = selftestDir
        doLast {
            dir.deleteRecursively()
            dir.mkdirs()
            dir.resolve("options.txt").writeText(
                listOf("onboardAccessibility:false", "narrator:0", "soundCategory_master:0.0", "renderDistance:2")
                    .joinToString("\n", postfix = "\n")
            )
        }
    }

    tasks.matching { it.name == "runSelftest" }.configureEach {
        dependsOn(prepareSelftest)
        // One game client at a time: they are heavy under software OpenGL.
        val earlier = rootProject.childProjects.keys.filter { it < project.name }.sorted()
        mustRunAfter(earlier.map { ":$it:runSelftest" })
    }

    tasks.register("selftest") {
        group = "verification"
        description = "Starts a headless client and checks the compressed atlases against their sprites (TesseraSelfTest)."
        dependsOn("runSelftest")
        val report = selftestDir.resolve("tessera-selftest.txt")
        doLast {
            if (!report.isFile) {
                throw GradleException("Tessera self-test: no report in $report (the client stopped before the check)")
            }
            val text = report.readText()
            println(text)
            if (!text.lineSequence().first().endsWith("PASS")) {
                throw GradleException("Tessera self-test failed: see $report")
            }
        }
    }
}

// The native bridge the JNI test loads: staged by collectNatives from natives/
// into the build directory, then put on the test classpath.
sourceSets {
    test {
        resources.srcDir(layout.buildDirectory.dir("generated/natives"))
    }
}

tasks.named("processTestResources") {
    dependsOn("collectNatives")
}

// -PrequireNative=true makes a missing library a failure instead of a skip
// (NativeBridgeSelfTest and verifyJniSymbols). The release gate sets it.
val requireNative = (findProperty("requireNative") as String?) == "true"

tasks.named<Test>("test") {
    systemProperty("tessera.requireNative", requireNative.toString())
}

// JNI symbols of the staged libraries against the native methods declared in the
// Java sources (scripts/verify-jni-symbols.sh). Part of `check`.
val verifyJniSymbols = tasks.register<Exec>("verifyJniSymbols") {
    group = "verification"
    description = "Checks the exported Java_* symbols of natives/ against the Java native methods."
    workingDir = rootProject.projectDir
    commandLine(
        "sh", rootProject.file("scripts/verify-jni-symbols.sh").path,
        rootProject.file("natives").path,
        rootProject.file("src/main/java").path
    )
    environment("REQUIRE_LIBRARIES", if (requireNative) "1" else "0")
}

tasks.named("check") {
    dependsOn(verifyJniSymbols)
}
