import com.android.build.api.dsl.ApplicationExtension
import com.spotineil.buildlogic.configureCompose
import com.spotineil.buildlogic.configureKotlinAndroid
import com.spotineil.buildlogic.libs
import com.spotineil.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureCompose(this)
            defaultConfig.targetSdk = libs.version("targetSdk").toInt()
        }
    }
}
