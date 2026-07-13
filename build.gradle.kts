import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.util.Properties

plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

version = "0.1.6"

group = "com.bilicraft.handheld.stockplugin"

android {
    namespace = "com.bilicraft.handheld.stockplugin"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    compileOnly(composeBom)
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.material:material-icons-extended")
    compileOnly("androidx.activity:activity-compose:1.9.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

fun androidSdkDir(): File {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank()) return file(fromEnv)

    val localProperties = Properties()
    val localFile = file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(localProperties::load)
        localProperties.getProperty("sdk.dir")?.let { return file(it) }
    }

    error("找不到 Android SDK。请设置 ANDROID_HOME，或在 local.properties 中写入 sdk.dir。")
}

fun d8Executable(): File {
    val buildTools = androidSdkDir().resolve("build-tools")
    val d8Name = if (System.getProperty("os.name").lowercase().contains("windows")) "d8.bat" else "d8"
    return buildTools.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .map { it.resolve(d8Name) }
        .lastOrNull { it.exists() }
        ?: error("Android SDK build-tools 中找不到 $d8Name")
}

val pluginId = "stock-market-dashboard"
val pluginEntryClass = "com.bilicraft.handheld.stockplugin.StockMarketPlugin"
val pluginManifestDir = layout.buildDirectory.dir("bhplugin/manifest")
val pluginManifestFile = pluginManifestDir.map { it.file("plugin.json") }
val extractedClassesDir = layout.buildDirectory.dir("bhplugin/classes")
val dexOutputDir = layout.buildDirectory.dir("bhplugin/dex")

val writePluginManifest by tasks.registering {
    outputs.file(pluginManifestFile)
    doLast {
        val file = pluginManifestFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "id": "$pluginId",
              "name": "股市面板",
              "description": "外部加载的帕拉伦股市面板插件。",
              "version": "$version",
              "apiVersion": 1,
              "entryClass": "$pluginEntryClass"
            }
            """.trimIndent()
        )
    }
}

val extractReleaseClassesJar by tasks.registering(Copy::class) {
    dependsOn("bundleReleaseAar")
    val aarFile = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
    from({ zipTree(aarFile.get().asFile) }) {
        include("classes.jar")
    }
    into(extractedClassesDir)
}

val dexReleaseClasses by tasks.registering(Exec::class) {
    dependsOn(extractReleaseClassesJar)
    val androidJar = androidSdkDir().resolve("platforms/android-34/android.jar")
    doFirst { dexOutputDir.get().asFile.mkdirs() }
    commandLine(
        d8Executable().absolutePath,
        "--release",
        "--min-api", "24",
        "--lib", androidJar.absolutePath,
        "--output", dexOutputDir.get().asFile.absolutePath,
        extractedClassesDir.get().file("classes.jar").asFile.absolutePath
    )
}

tasks.register<Zip>("packageBhPlugin") {
    dependsOn(writePluginManifest, dexReleaseClasses)
    archiveFileName.set("stock-market-$version.bhplugin")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/bhplugin"))
    from(pluginManifestFile) {
        rename { "plugin.json" }
    }
    from(dexOutputDir) {
        include("classes.dex")
    }
    from("src/main/assets") {
        into("assets")
    }
}