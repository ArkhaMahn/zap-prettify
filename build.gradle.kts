import org.zaproxy.gradle.addon.AddOnStatus

plugins {
    java
    id("org.zaproxy.add-on") version "0.13.1"
}

group = "prettify"
version = "1.0.0"
description = "Prettify - syntax-highlighted view of HTTP requests and responses"

repositories {
    mavenCentral()
}

java {
    // ZAP 2.17.0 requires Java 17 or above.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

zapAddOn {
    addOnName.set("Prettify")
    addOnStatus.set(AddOnStatus.ALPHA)
    zapVersion.set("2.17.0")

    manifest {
        author.set("Prettify")
        url.set("https://github.com/")
        bundle {
            baseName.set("org.zaproxy.zap.extension.prettify.resources.Messages")
            prefix.set("prettify")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
