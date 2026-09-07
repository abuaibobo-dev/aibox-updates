plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="com.aurora.toolbox.mobile"; compileSdk=35
    defaultConfig { applicationId="com.aurora.toolbox.mobile"; minSdk=26; targetSdk=35; versionCode=1; versionName="0.1.0" }
    buildFeatures { compose=true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
