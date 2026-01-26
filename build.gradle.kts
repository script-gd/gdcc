plugins {
    id("java")
}

group = "dev.superice"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
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