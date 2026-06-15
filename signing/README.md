# CI/Test Signing Configuration

This directory contains the keystore used for signing APKs in the CI/CD pipeline for internal testing.

## Keystore Details

- **Path:** `signing/test.keystore`
- **Password:** `password`
- **Alias:** `testAlias`
- **Key Password:** `password`

## Usage in Gradle

The signing configuration is defined in each module's `build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../signing/test.keystore")
        storePassword = "password"
        keyAlias = "testAlias"
        keyPassword = "password"
    }
}

buildTypes {
    release {
        // ...
        signingConfig = signingConfigs.getByName("release")
    }
}
```

**Note:** This keystore is for testing purposes only. Do not use it for production release on the Play Store.
