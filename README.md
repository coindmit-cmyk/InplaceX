# InplaceX Repository

Top-level structure:

- `InplaceX-android` - Android client and game runtime
- `InplaceX-backend` - JVM backend module, including the server-side bot player adapter
- `InplaceX-docs` - human and GPT documentation, design references, legacy notes

Recommended working split:

- gameplay, UI, mobile integration -> `InplaceX-android`
- sync, PvP services, server bot runtime, ranking, cloud logic -> `InplaceX-backend`
- product, architecture, ADR, design references -> `InplaceX-docs`
