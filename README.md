# VisionTap

VisionTap is an Android screen-image detector with optional automatic tapping. It uses MediaProjection for user-approved screen capture, OpenCV ORB descriptors for scale/rotation-tolerant matching, RANSAC homography to reject inconsistent matches, and AccessibilityService gestures for tapping.

## Features

- Choose a reference image from device storage.
- Scan the visible screen continuously while the foreground capture notification is active.
- Match under moderate size and rotation changes and tolerate partial visibility when enough distinctive features remain.
- Estimate the click point from inlier scene features, so the tap can stay inside the visible fragment.
- Require two consecutive detections before tapping and wait for the target to disappear before tapping it again.
- Build a debug APK with GitHub Actions.

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 35, or run `gradle assembleDebug` with Gradle 8.9+. The GitHub Actions workflow installs Gradle 8.9, builds the debug APK, and uploads it as the `visiontap-debug-apk` artifact.

## Use

1. Install `app-debug.apk` on the phone.
2. Open VisionTap and select a reference image.
3. Enable **VisionTap auto click** under Android Accessibility settings if automatic tapping is desired.
4. Start detection and approve Android's screen-capture prompt.
5. Keep the ongoing VisionTap notification visible while scanning. Use Stop to end the session.

The app needs no root access. Screen capture permission must be granted by the user for each new session. The accessibility service is used only to dispatch the tap gesture requested by the user.

## Matching limitations

ORB is feature-based, not semantic AI. Partial matching works only when the visible fragment contains enough unique visual features; plain shapes, tiny images, repeated patterns, heavy blur, and major visual redesigns can fail or produce ambiguous matches. Test with automatic tapping disabled first when tuning for an unfamiliar image. No detector can guarantee zero false positives.

## Project

- `MainActivity`: image selection and permissions
- `ScreenCaptureService`: MediaProjection foreground service and frame loop
- `ImageDetector`: ORB + BFMatcher + RANSAC
- `ClickAccessibilityService`: Android gesture dispatch
