import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("com.android.library")
	kotlin("android")
}

kotlin {
	jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
	namespace = "wah.mikooomich.ffMetadataEx"
	compileSdk = 36

	defaultConfig {
		minSdk = 24

		externalNativeBuild {
			cmake {
				arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none")
			}
		}
	}

	sourceSets {
		getByName("main") {
			jniLibs.srcDirs("ffmpeg-android-maker/output/lib/")
		}
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}

	ndkVersion = "28.0.12674087"

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	implementation(libs.annotation)
	implementation(libs.media3)
}
