# CameraXUtils

CameraXUtils is an Android library built on top of CameraX that provides a ready-to-use camera bottom sheet for capturing photos with common camera controls and a simple callback-based result flow.

## Features

- Front/back camera switching
- Photo capture
- Flash modes: off, auto, on
- Grid overlay toggle
- HDR support when available on the device
- Exposure compensation control
- Timer options: 3s and 10s
- Zoom support with pinch gestures
- Returns the saved image URI through a callback

## Project Structure

This repository contains two main modules:

- `camerax`: the reusable Android library
- `sample`: a demo app that consumes the library

## Requirements

- Android Studio
- Kotlin
- Android SDK
- minSdk: 21
- compileSdk: 33

## Installation

For a local Gradle setup, add the module as a project dependency:

```gradle
dependencies {
    implementation project(':camerax')
}
```

The project is also configured for JitPack publishing, so it can be consumed via JitPack in a standard Android project.

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.stoufam71:cameraxutils:<tag>'
}
```

## Usage

Open the camera utility as a fragment and receive the captured URI:

```kotlin
import android.net.Uri
import com.jds.camerax.CameraXUtils

CameraXUtils.newInstance { uri: Uri ->
    // Handle the captured image uri here
}.show(supportFragmentManager, "camera")
```

This library exposes a `CameraXUtils` fragment that internally manages:
- camera binding
- preview setup
- image capture
- permissions
- UI state such as flash, grid, HDR, and timer preferences

## Sample App

The `sample` module demonstrates how the library is used in a real Android application. It includes a minimal app setup and integration with the `camerax` module.

## Build

This project uses Gradle and can be built with the standard Android project commands:

```bash
./gradlew build
```

or in Android Studio, run the app configuration for the `sample` module.

## Notes

The library is designed as a convenience layer around CameraX and focuses on a compact, UI-driven image capture workflow. It is especially useful for apps that want a quick, reusable camera experience without building the camera logic from scratch.

## Contributing

If you want to extend or improve the project, you can modify the library module and validate it using the included sample app.

## Summary

CameraXUtils is a small Android CameraX utility library that simplifies image capture in apps by wrapping CameraX functionality into an easy-to-use fragment with built-in controls for flash, exposure, timer, HDR, and camera switching.
