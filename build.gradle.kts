plugins {
    groovy
    // Builds the shadow (fat) jar that bundles the non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "AnnoTools"
    group = "io.github.qupath"
    version = "1.0.0"
    description = "A simple QuPath extension to easily modify annotations across a project"
    automaticModule = "qupath.ext.annotools"
}

// The shadow jar is the one users install, so skip the plain jar and give the shadow jar the plain name
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier = ""
}

// Make a plain `gradlew build` produce the installable jar
tasks.build {
    dependsOn(tasks.shadowJar)
}

dependencies {

    // Dependencies provided by QuPath, bundled into the shadow jar
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.bundles.groovy)

}
