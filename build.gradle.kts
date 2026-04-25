plugins {
    id("java")
    id("org.gradlex.extra-java-module-info") version "1.9"
}

group = "dev.superice"
version = "1.0-SNAPSHOT"

val generatedVersionResources = layout.buildDirectory.dir("generated/resources/gdccVersion")
val runtimeLibDir = layout.buildDirectory.dir("libs/lib")

fun gitOutput(vararg args: String): String {
    return try {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        if (process.waitFor() == 0) output else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    compileOnly("org.jetbrains:annotations:24.0.1")
    testCompileOnly("org.jetbrains:annotations:24.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.freemarker:freemarker:2.3.34")
    implementation("com.github.abrarsyed.jastyle:jAstyle:1.3")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("com.github.SuperIceCN:gdparser:0.5.1")
}

tasks.test {
    useJUnitPlatform()
}

val generateVersionResource by tasks.registering {
    val versionFile = generatedVersionResources.map { it.file("gdcc-version.properties") }
    outputs.file(versionFile)
    outputs.upToDateWhen { false }

    doLast {
        val outputFile = versionFile.get().asFile
        outputFile.parentFile.mkdirs()

        val branch = gitOutput("rev-parse", "--abbrev-ref", "HEAD").ifBlank { "unknown" }
        val commit = gitOutput("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
        outputFile.writeText(
            """
                version=$version
                branch=$branch
                commit=$commit
                """.trimIndent() + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

tasks.processResources {
    dependsOn(generateVersionResource)
}

val syncRuntimeLibs by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies runtime dependencies next to the generated jar."

    from(configurations.runtimeClasspath)
    into(runtimeLibDir)
}

tasks.jar {
    dependsOn(syncRuntimeLibs)
    manifest {
        attributes(
            "Main-Class" to "dev.superice.gdcc.Main",
            "Class-Path" to configurations.runtimeClasspath.get()
                .files
                .sortedBy { it.name }
                .joinToString(" ") { "lib/${it.name}" }
        )
    }
}

val packageDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the gdcc jar and sibling lib dependencies into a zip archive."

    dependsOn(tasks.jar, syncRuntimeLibs)
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.jar.flatMap { it.archiveFile })
    from(runtimeLibDir) {
        into("lib")
    }
}

sourceSets {
    main {
        resources {
            srcDir("src/main/c/codegen")
            srcDir(generatedVersionResources)
        }
    }
    test {
        resources {
            srcDir("src/test/test_suite")
        }
    }
}

extraJavaModuleInfo {
    automaticModule("jAstyle-1.3.jar", "jAstyle")
    automaticModule("tree-sitter-0.26.3.jar", "tree.sitter")
}
