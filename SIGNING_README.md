# Testing Keystore Information

This repository contains a dedicated keystore for deterministic CI/CD builds.

## Keystore Details
- **File:** `test.jks` (located in root)
- **Alias:** `testkey`
- **Store Password:** `password123`
- **Key Password:** `password123`

## Usage in Gradle
The `build.gradle.kts` files are configured to use this keystore for release builds when running in a CI environment or during standard `assembleRelease` tasks for testing purposes.
