// Load local.properties into project extra properties so all submodules
// can read them via project.findProperty()
val localProperties = java.util.Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
    localProperties.forEach { key, value ->
        ext.set(key.toString(), value.toString())
    }
}

plugins {
    id("com.android.library") version "8.9.1" apply false
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
