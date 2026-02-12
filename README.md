# Astronomiya AR (based on Couch Mirage)

This Android app is built with **Kotlin**, **ARCore**, and **Sceneform**. In this workspace it has been repurposed as an **astronomy learning** app (UI title: *Astronomiya AR*):

- Loads `categories` and `celestial_bodies` from **Firebase Firestore**
- Downloads `.glb` models from **Firebase Storage**
- Shows models in **AR** (`OpenCameraActivity`) and in a **3D viewer** (`ModelViewActivity`)

## Quick start (dev)

1. Open `couch-mirage/` in Android Studio.
2. Add Firebase config at `app/google-services.json` (do not commit it).
3. Build:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Run on an ARCore-supported device.

## Firebase schema (expected by current code)

Firestore collections:

- `categories` — docs like:
  - `name` (string)
  - `order` (number)
- `celestial_bodies` — docs like:
  - `name` (string)
  - `category` (string, matches a `categories` doc id)
  - `modelUrl` (string; storage path like `models/earth.glb` or a full `gs://` / `https://` URL)
  - `description`, `facts`, etc (optional)

## Project structure (high level)

```
app/src/main/java/com/huji/couchmirage/
├── ar/
│   ├── CameraFacingNode.kt
│   └── MyArFragment.kt
├── catalog/
│   ├── CatalogFrontActivity.kt
│   ├── CategoryActivity.kt
│   ├── FirebaseRepository.kt
│   ├── ItemDetailsActivity.kt
│   ├── ModelViewActivity.kt
│   ├── Category.kt
│   └── CelestialBody.kt
├── Help/ (help screens)
├── greetings/ (onboarding screens)
└── OpenCameraActivity.kt
```

## Supported devices

ARCore supported devices list:
https://developers.google.com/ar/discover/supported-devices

## Notes

- Sceneform is a legacy stack; keep dependencies pinned and test AR on your target devices.
- Never commit secrets (service account keys, `google-services.json`, keystores).

