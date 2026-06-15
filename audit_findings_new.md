# Audit Findings - UI Stabilization and Feature Enhancements

## 1. List Flicker and Performance
- **Cause**: Current `LazyColumn` implementations in `DashboardScreen` and `DeviceDetailScreen` lack stable item keys (`key = { ... }`).
- **Cause**: Firestore `snapshots()` flows emit on every change. Without `distinctUntilChanged()`, the UI may recompose even if relevant data hasn't changed.
- **Cause**: Sorting is currently inconsistent; some lists use `orderBy` but others rely on default Firestore ordering.

## 2. Notification Filtering
- **Issue**: The current implementation groups notifications by app but doesn't allow filtering by app.
- **Requirement**: A horizontal scrollable list of apps with notification counts.
- **Requirement**: "All" filter to show everything.

## 3. SMS/Messages Enhancements
- **Requirement**: OTP detection (4-8 digits).
- **Requirement**: Copy OTP button with Snackbar feedback.
- **Requirement**: Inbox/Sent filter chips.

## 4. Device Status
- **Requirement**: Online/Offline indicator in Dashboard.
- **Metric**: If `lastSyncTime` is within the last 5 minutes, mark as Online.

## 5. Sorting Requirements
- **Notifications**: `timestamp` DESC.
- **SMS**: `date` DESC.
- **Calls**: `date` DESC.
- **Contacts**: Alphabetical (Name) or `lastUpdated` DESC.

## 6. UI Consistency
- **Requirement**: Pull-to-refresh on Dashboard and possibly Detail tabs.
- **Requirement**: Unified Material 3 design with clean dark theme support.
