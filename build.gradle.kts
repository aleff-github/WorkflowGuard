plugins {
    java
}

group = "dev.workflowguard"
version = "0.3.3"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")
    implementation("com.google.re2j:re2j:1.8")

    testImplementation("net.portswigger.burp.extensions:montoya-api:2026.7")
    testImplementation(project(":fixture"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName = "workflowguard"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-WORKFLOWGUARD" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.file("licenses/RE2J-LICENSE.txt")) {
        into("META-INF")
        rename { "LICENSE-RE2J" }
    }

    from(configurations.runtimeClasspath.get().filter { it.isDirectory })
    from(configurations.runtimeClasspath.get().filterNot { it.isDirectory }.map { zipTree(it) })

    manifest {
        attributes(
            "Implementation-Title" to "WorkflowGuard",
            "Implementation-Version" to project.version
        )
    }
}
