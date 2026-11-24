plugins { kotlin("jvm") }

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test { useJUnitPlatform() }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Produces a classpath for the independent python-OBD probe without holding Gradle open.
tasks.register("compatibilityClasspath") {
    dependsOn(tasks.testClasses)
    val target = layout.buildDirectory.file("compatibility-classpath.txt")
    outputs.file(target)
    doLast { target.get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
