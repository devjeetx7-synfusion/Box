# Audit Findings and Crash Investigation - Admin App

## 1. Root Cause of Crash
The primary cause of the crash is the incorrect instantiation of Hilt-injected ViewModels in `MainActivity.kt`.
- **Issue**: `DashboardViewModel` and `DeviceDetailViewModel` are annotated with `@HiltViewModel` and use constructor injection. However, they are being called using the standard `viewModel()` delegate from `androidx.lifecycle:lifecycle-viewmodel-compose`.
- **Impact**: The ViewModelProvider fails to find a no-arg constructor and crashes during runtime.
- **Fix**: Use `hiltViewModel()` from `androidx.hilt:hilt-navigation-compose`.

## 2. Identified Bugs & Stability Issues
- **Incorrect Device Metadata**: `DeviceDetailScreen` displays the hardware information of the device running the Admin App (using `android.os.Build`) instead of the synchronized device metadata.
- **Lifecycle Safety**: Flows are collected using `collectAsState()`, which keeps the flow active even when the app is in the background.
- **Repository Architecture**: `AdminRepositoryImpl` manually retrieves the Firestore instance instead of using dependency injection, making it harder to test.
- **UI States**: Lack of loading and empty states across most screens.
- **Data Refresh**: No manual pull-to-refresh mechanism for real-time data updates.

## 3. Dependency Gaps
- `androidx.hilt:hilt-navigation-compose` is missing.
- `androidx.lifecycle:lifecycle-runtime-compose` is missing.

## 4. Planned Improvements
- Harmonize `Device` model with `user-app` to include hardware specs.
- Implement `collectAsStateWithLifecycle` for all Firestore streams.
- Refactor DI to include a `FirebaseModule`.
- Implement Material 3 `PullToRefreshBox` and better state handling.
