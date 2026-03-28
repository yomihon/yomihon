# Android DevContainer workflow

This repository includes a DevContainer setup for Android development on macOS without installing the Android toolchain on the host.

## What lives where

- Host: Docker Desktop and VS Code with the Dev Containers extension
- Container: Java 17, Android SDK, build tools, NDK, Gradle
- Phone: ADB wireless debugging over Wi-Fi

## Repo-local state that is persisted

- `.gradle-cache/` keeps Gradle downloads between container rebuilds
- `.android-adb/` keeps ADB client keys and wireless pairing state between container rebuilds

Both directories are gitignored and are created automatically when VS Code starts the container.

## First open

1. Open the repository in VS Code.
2. Run `Dev Containers: Reopen in Container`.
3. Wait for the image build to finish.

The container image installs the SDK pieces this repo currently needs:

- Java 17
- Android platform `36`
- Android platform `35`
- Build tools `35.0.1`
- NDK `27.1.12297006`
- `platform-tools`

`local.properties` is also written automatically inside the workspace with `sdk.dir=/opt/android-sdk`.

On Apple Silicon Macs, this container should run as `linux/amd64`. In practice, the official Android Linux toolchain used by Gradle, including `adb` and `aapt2` from the SDK packages, is x86_64-only. Keeping the whole container on one architecture avoids mixed-architecture failures during resource processing and packaging.

## Pair a phone the first time

Requirements:

- Android 11 or later
- Developer options enabled
- Wireless debugging enabled
- Phone and Mac on the same Wi-Fi network

On the phone:

1. Open `Settings -> Developer options -> Wireless debugging`.
2. Tap `Pair device with pairing code`.
3. Note the pairing address and six-digit PIN.

In the container terminal:

```bash
./scripts/adb-wireless pair 192.168.1.50:37895
```

Enter the PIN when prompted.

Because `/home/vscode/.android` is backed by `.android-adb/`, pairing survives container rebuilds.

## Daily connect flow

Each wireless debugging session exposes a fresh debug port on the phone.

1. Open `Settings -> Developer options -> Wireless debugging`.
2. Note the current debug address, for example `192.168.1.50:42341`.
3. In the container terminal, connect:

```bash
./scripts/adb-wireless connect 192.168.1.50:42341
```

To confirm the device is visible:

```bash
./scripts/adb-wireless devices
```

## Build and test

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew connectedAndroidTest
```

## Install more SDK packages later

```bash
sdkmanager --list | grep "platforms;android"
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

The SDK directory is writable by the `vscode` user, so you can add packages from inside the container.

## Troubleshooting

- `adb: no devices/emulators found`: reconnect with the current debug address shown on the phone
- Pairing stops working after a reset on the phone: remove the saved device from the phone and pair again
- `sdk.dir` errors: rerun `Dev Containers: Rebuild Container` so the post-create step rewrites `local.properties`
- Gradle cache is empty after reopening: make sure `.gradle-cache/` exists in the repo root and the container reopened successfully
- ADB pairing did not persist: make sure `.android-adb/` exists in the repo root and is mounted into the container
