# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.13] - 2026-09-18

### Added
- Optional FCM add-on (`co.rivium:rivium-push-fcm`). A message delivered over both paths is shown once.
- `installId` on register, so a reinstall no longer leaves a stale device behind.

## [0.1.12] - 2026-09-14

### Added
- SDK identity: the SDK reports its name and version (`RiviumPush.SDK_VERSION`).
- `autoRefresh` (default `true`): keeps a registered device up to date on launch.

### Changed
- Delivery confirmations are sent once per message and retried on network errors.

### Fixed
- `clearUserId()` failing offline could leave the previous user attached to the device.
- README: topic methods are `subscribeTopic`/`unsubscribeTopic`; removed options that don't exist.

## [0.1.11] - 2026-09-08

### Added
- Delivery receipts. The SDK now confirms to the server that a notification actually reached the device, so delivery can be told apart from "accepted by the transport".

## [0.1.10] - 2026-08-23

### Added
- App build number captured alongside app version.

## [0.1.9] - 2026-08-23

### Added
- Device attributes captured automatically for segment filters.

## [0.1.8] - 2026-08-18

### Fixed
- App crashed on start on Android <14 (`foregroundServiceType 0x00000001 is not a subset of 0x40000000`). The 0.1.7 manifest declared only `specialUse` but the code still requested `dataSync` on older OS versions. Now Android <14 calls `startForeground` without a type flag.

## [0.1.7] - 2026-08-17

### Fixed
- App crashed at boot on Android 15+ (`ForegroundServiceStartNotAllowedException: FGS type dataSync not allowed to start from BOOT_COMPLETED`). Switched the foreground service to `specialUse`.
