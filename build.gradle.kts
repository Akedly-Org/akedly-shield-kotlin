plugins {
    kotlin("jvm") version "1.9.22"
    `maven-publish`
}

group = "com.akedly"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.akedly"
            artifactId = "shield"
            version = project.version.toString()
            from(components["java"])
        }
    }
}
