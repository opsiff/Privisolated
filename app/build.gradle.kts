plugins {
    id("com.android.application")
}

android {
    enableKotlin = false
    namespace = "org.lsposed.privisolated"
    defaultConfig {
        applicationId = "org.lsposed.privisolated"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            vcsInfo.include = false
            signingConfig = signingConfigs["debug"]
            optimization {
                enable = true
                keepRules {
                    includeDefault = false
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
    }
}
