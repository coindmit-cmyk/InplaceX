# InplaceX Test Support

Shared JVM test helpers for InplaceX modules.

## Responsibilities

- provide reusable test sinks for `InplaceX-logging`;
- provide reusable console output adapters for benchmark and diagnostic runners;
- keep ad-hoc test infrastructure out of production modules.

## Current API

- `RecordingLogSink`
- `ConsoleLogSink`

## Usage

- use from test source sets and manual benchmark runners;
- do not depend on this module from production code paths.

## Verification

```powershell
.\gradlew.bat :InplaceX-test-support:test
```
