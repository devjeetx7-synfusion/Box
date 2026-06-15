# Android Reference Architecture - Project Notes

## Architecture Overview
This project demonstrates a modern Android architecture based on **Clean Architecture** principles, utilizing **Jetpack Compose** for the UI layer and **Dagger Hilt** for dependency injection.

### Layers:
- **UI Layer**: Built entirely with Jetpack Compose (Material 3). It follows the Unidirectional Data Flow (UDF) pattern using ViewModels and StateFlow.
- **Domain Layer**: Contains business logic and models. It is agnostic of any specific framework.
- **Data Layer**: Handles data retrieval and synchronization. It includes repositories that interact with Firebase Firestore and local system providers (Contacts, SMS, Call Logs).

## Key Features & Patterns
- **Real-time Synchronization**: Uses `ContentObserver` in a `ForegroundService` (Client App) to detect local data changes and `SnapshotListeners` (Admin App) for real-time dashboard updates.
- **Background Processing**: Implements WorkManager for scheduled periodic syncs (30-min intervals) with network constraints.
- **Security**: Device identification is persisted locally using `EncryptedSharedPreferences`.
- **Modern Permissions**: A multi-step permission flow provides educational rationales before requesting runtime permissions and specialized system access (Notification Listener).
- **Data Optimization**: Utilizes Firestore Batch Writes (chunked at 500 docs) to ensure efficient network and database usage.

## Known Limitations
- **Notification Capture**: Requires manual user intervention to enable Notification Access in system settings.
- **Data Volume**: Initial sync of very large datasets (e.g., thousands of SMS) might take some time and consume battery; optimized via chunked batch writes.
- **Permissions**: If permissions are permanently denied, users must go to system settings to re-enable them.

## Testing Steps
1. **Manual Verification**:
   - Launch the Client App and follow the permission flow.
   - Click "Sync Now" and verify the status changes to "Success".
   - Open the Admin App and confirm the device appears in the dashboard with correct metrics.
   - Send a test SMS or add a contact to the device and verify real-time update in the Admin App.
2. **Build Check**:
   - Run `./gradlew assembleDebug` to verify compilation.
   - Run `./gradlew test` to execute unit tests.
3. **Architecture Validation**:
   - Ensure no direct repository instantiation in UI (must use Hilt/ViewModels).
   - Verify package structure: `data/`, `domain/`, `ui/`.
