# shared — Kotlin Multiplatform core

All business logic lives here (data, event journal, capture, search, ML, licensing). Native apps only do UI,
permissions, system APIs and inject platform implementations through `di/SharedFactory`.

Invariants:
- `explicitApi()` and warnings-as-errors: everything public is deliberately public.
- No network code anywhere except `ml/ModelPackDownloader` (not implemented in v1).
- No business data in logs: identifiers only (`log/Logger`). Same for exception messages thrown by this module —
  they end up in debug logs and in Swift `LocalizedError`s.
- `expect`/`actual` only in `platform/`; prefer an interface implemented by the host app when a SDK is involved.
