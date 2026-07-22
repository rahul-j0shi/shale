// Root build. Shared configuration applied to every module; per-module dependencies
// (and the module dependency direction) live in each module's own build script.
//
// Conventions enforced here:
//   - Java 25 toolchain (java-style.md §2), sourced from the in-repo JDK.
//   - Google Java Format via Spotless (java-style.md §1).
//   - Checkstyle with the project's banned-API config (java-style.md §7).
//   - Tagged test tiers: `test` (fast), `crashTest`, `soakTest` (testing.md).

plugins {
    id("com.diffplug.spotless") version "7.0.4" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "com.diffplug.spotless")

    group = "dev.shale"
    version = "0.0.1-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = "10.21.4"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
        isIgnoreFailures = false
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.35.0")
            toggleOffOn()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // Compile with the project's expectations made explicit.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    // Forward `shale.*` system properties (e.g. -Dshale.test.seed) to the forked test JVM,
    // so a failing seeded test can be reproduced from the command line (testing.md §2).
    tasks.withType<Test>().configureEach {
        System.getProperties().forEach { (key, value) ->
            if (key.toString().startsWith("shale.")) {
                systemProperty(key.toString(), value.toString())
            }
        }
    }

    val testSources = extensions.getByType<SourceSetContainer>()

    // Fast unit tests: everything except the slow, tagged tiers.
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("crash", "soak") }
    }

    // N8: no Thread.sleep — these run on the injected Clock and seeded randomness.
    tasks.register<Test>("crashTest") {
        group = "verification"
        description = "Fault-injection / crash-recovery suite (JUnit tag: crash)."
        testClassesDirs = testSources["test"].output.classesDirs
        classpath = testSources["test"].runtimeClasspath
        useJUnitPlatform { includeTags("crash") }
    }

    tasks.register<Test>("soakTest") {
        group = "verification"
        description = "Long-running randomised model check (JUnit tag: soak)."
        testClassesDirs = testSources["test"].output.classesDirs
        classpath = testSources["test"].runtimeClasspath
        useJUnitPlatform { includeTags("soak") }
    }
}
