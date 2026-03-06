plugins {
    id("java")
    id("org.gradlex.extra-java-module-info") version "1.9"
}

group = "dev.superice"
version = "1.0-SNAPSHOT"

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
    implementation("com.github.SuperIceCN:gdparser:0.3.0")
}

tasks.test {
    useJUnitPlatform()
}

sourceSets {
    main {
        resources {
            srcDir("src/main/c/codegen")
        }
    }
}

extraJavaModuleInfo {
    automaticModule("jAstyle-1.3.jar", "jAstyle")
    automaticModule("tree-sitter-0.26.3.jar", "tree.sitter")
}