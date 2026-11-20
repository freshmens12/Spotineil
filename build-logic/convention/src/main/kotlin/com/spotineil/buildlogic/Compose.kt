package com.spotineil.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureCompose(application: ApplicationExtension) {
    application.buildFeatures.compose = true
    configureComposeDependencies()
}

internal fun Project.configureCompose(library: LibraryExtension) {
    library.buildFeatures.compose = true
    configureComposeDependencies()
}

private fun Project.configureComposeDependencies() {
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        add("implementation", platform(bom))
        add("testImplementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
    }
}
