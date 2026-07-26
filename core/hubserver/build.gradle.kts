plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
}

sqldelight {
    databases {
        create("HubDatabase") {
            packageName.set("ph.chefdonn.hubserver.db")
        }
    }
}

dependencies {
    api(project(":core:protocol"))
    api(libs.sqldelight.runtime)
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(project(":core:client"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}
