# Firebase Setup Instructions

## 1. Create a Firebase Project
- Go to the [Firebase Console](https://console.firebase.google.com/).
- Click "Add project" and follow the instructions.
- Disable Google Analytics for this project (optional).

## 2. Add Android Apps to Firebase
You need to add two apps to your Firebase project:

### User App
- Package name: `com.datasync.user`
- Download the `google-services.json` file.
- Place it in the `user-app/` directory.

### Admin App
- Package name: `com.datasync.admin`
- Download the `google-services.json` file.
- Place it in the `admin-app/` directory.

## 3. Enable Firestore
- In the Firebase Console, go to **Build > Cloud Firestore**.
- Click **Create database**.
- Choose a location and start in **production mode** or **test mode**.
- Once created, go to the **Rules** tab.

## 4. Firestore Security Rules
Copy and paste the following rules into the Rules tab and click **Publish**:

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /devices/{deviceId} {
      allow read: if true;           // Admin can read everything
      allow write: if resource == null || request.auth == null; // Anyone can write their own device data (no auth)
    }
    match /devices/{deviceId}/{collection}/{doc} {
      allow read: if true;
      allow write: if request.auth == null;
    }
  }
}
```

## 5. Build and Run
- Ensure you have the `google-services.json` files in the respective directories.
- Sync Project with Gradle Files.
- Build and run the `user-app` on a device or emulator.
- Build and run the `admin-app` to view the synced data.
