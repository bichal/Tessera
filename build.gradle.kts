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
// -PselftestDefaultPolicy=true runs the self-test with the shipped defaults (animated atlases stay
// RGBA8888); otherwise it opts in to compressing them, which is the path it checks frame by frame.
// -PselftestMods=<jar>[,<jar>] adds mods to a Fabric run (e.g. a stand-in for Kerria).
val selftestDefaultPolicy = (findProperty("selftestDefaultPolicy") as String?) == "true"
val selftestMods = findProperty("selftestMods") as String?
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
            // Unless told otherwise the self-test checks animations on compressed atlases (the opt-in BC path).
            if (!selftestDefaultPolicy) systemProperty("tessera.compressAnimated", "true")
            systemProperty("tessera.selftest.version", modProps.currentVersion)
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
val accessWidener = if (modProps.mcVersion.startsWith("1.")) "tessera-named.accesswidener" else "tessera-official.accesswidener"
if (modProps.isFabric) {
    extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
        accessWidenerPath.set(rootProject.file("src/main/resources/$accessWidener"))
        runs {
            // Headless client check of the compressed atlases: TesseraSelfTest.
            create("selftest") {
                client()
                vmArg("-Dtessera.selftest=true")
                if (!selftestDefaultPolicy) vmArg("-Dtessera.compressAnimated=true")
                if (selftestMods != null) vmArg("-Dfabric.addMods=$selftestMods")
                vmArg("-Dtessera.selftest.version=${modProps.currentVersion}")
                vmArg("-Xmx2G")
            }
        }
    }
    // After bichal.fabric-mod's afterEvaluate, which sets every run's directory to "run".
    afterEvaluate {
        extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
            runs.named("selftest") { runDir("run-selftest") }
        }
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
