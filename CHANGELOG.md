# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.8] - 2026-08-18

### Fixed
- App crashed on start on Android <14 (`foregroundServiceType 0x00000001 is not a subset of 0x40000000`). The 0.1.7 manifest declared only `specialUse` but the code still requested `dataSync` on older OS versions. Now Android <14 calls `startForeground` without a type flag.

## [0.1.7] - 2026-08-17

### Fixed
- App crashed at boot on Android 15+ (`ForegroundServiceStartNotAllowedException: FGS type dataSync not allowed to start from BOOT_COMPLETED`). Switched the foreground service to `specialUse`.
