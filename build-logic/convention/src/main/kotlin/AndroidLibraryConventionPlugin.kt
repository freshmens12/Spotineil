import com.android.build.api.dsl.LibraryExtension
import com.spotineil.buildlogic.configureKotlinAndroid
import com.spotineil.buildlogic.lib
import com.spotineil.buildlogic.libs
import com.spotineil.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        // Gradle 9 fails modules that have no tests; that is fine here.
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach { failOnNoDiscoveredTests.set(false) }
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            testOptions.targetSdk = libs.version("targetSdk").toInt()
            lint.targetSdk = libs.version("targetSdk").toInt()
        }
        dependencies {
            add("testImplementation", libs.lib("junit4"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("testImplementation", libs.lib("turbine"))
            add("testImplementation", libs.lib("mockk"))
        }
    }
}
