plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdkVersion(Config.compileSdk)
    buildToolsVersion(Config.buildTools)

    defaultConfig {
        minSdkVersion(29)
        targetSdkVersion(Config.targetSdk)
    }
}

repositories {
    mavenCentral()
}

// dependencies
apply("$rootDir/common-dependencies.gradle")

tasks {
    val generateExtensions by registering {
        doLast {
            val isWindows = System.getProperty("os.name").toString().toLowerCase().contains("win")
            val classPath = (configurations.debugCompileOnly.get().asFileTree.toList() +
                listOf(
                    configurations.androidApis.get().asFileTree.first().absolutePath, // android.jar path
                    "$projectDir/build/intermediates/aar_main_jar/debug/classes.jar" // jar made from this module
                ))
                .joinToString(if (isWindows) ";" else ":")
            val javaPath = "${System.getProperty("java.home")}/bin/java"

            val mainClass = "generator.GeneratorMainKt" // Main class we want to execute

            val javaCommand = if (isWindows) {
                "\"$javaPath\" -classpath $classPath $mainClass".replace("/", "\\")
            } else {
                "$javaPath -classpath $classPath $mainClass"
            }
            val javaProcess = Runtime.getRuntime().exec(javaCommand)
            val exitCode = javaProcess.waitFor()
            if (exitCode != 0) {
                throw Exception("Java process failed with exit code: $exitCode")
            }
        }
        dependsOn("assembleDebug")
    }
}
