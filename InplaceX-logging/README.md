# InplaceX Logging

Shared JVM logging contract for InplaceX modules.

## Responsibilities

- define project log levels;
- define structured `LogEvent`;
- provide a `LogSink` boundary for platform-specific adapters;
- provide safe default no-op logging;
- redact sensitive attribute values by key before emitting events.

## Current API

- `LogLevel`
- `LogEvent`
- `LogSink`
- `NoOpLogSink`
- `LogSanitizer`
- `SensitiveKeyLogSanitizer`
- `InplaceXLogger`

## Safety Rules

Do not log:

- hidden game secrets;
- provider ids that are not safe placeholders;
- tokens;
- passwords;
- cookies;
- private keys;
- personal data.

Throwable messages are intentionally not included in `LogEvent`; they can contain secrets from lower-level SDKs.

## Verification

```powershell
.\gradlew.bat :InplaceX-logging:test
```
