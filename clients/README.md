---
commit: ba1c0d5
---

# Clients

The Windows and Android clients, on the branch `dev/windows`. The Apple clients in
`Apps/` and `Sources/` are built by XcodeGen and are not part of this Gradle build;
[the client design](../docs/platform-clients.md) and [the screen
specification](../docs/platform-screens.md) describe what these clients are.

```
settings.gradle.kts      the Gradle root: repositories and the module list
windows/                 the Windows client
win-audio/               a Rust spike that measures the WASAPI clock, not part of Gradle
```

## Build and run

JDK 22 and the wrapper. `./gradlew` is committed so no global Gradle is needed.

```
cd clients
./gradlew :windows:run          # opens the window
./gradlew :windows:compileKotlin
```

`win-audio` builds with Cargo: `cargo run --release --manifest-path win-audio/Cargo.toml`.

## Versions

| What | Version |
| --- | --- |
| Gradle | 9.8.2 (the wrapper) |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Compose Material 3 | 1.9.0 |
| JDK | 22, the toolchain every module targets |

## Why the mirrors come first

`settings.gradle.kts` lists `maven.aliyun.com` before Maven Central. The network this
project is developed on cannot reach `repo.maven.apache.org`, and the canonical
repositories remain listed after the mirrors, so a machine that can reach them still
resolves anything the mirrors lack. Moving the canonical repositories first is a
two-line change, not a restructure.

## What is still missing

- The window is a placeholder: the workbench, the library and the play screens come
  next, in the order the screen specification gives.
- No chart generation and no gameplay yet: the runtime binding (`ensomi-runtime-ffi`
  through JNA) has not been wired in.
- Android has no module yet.
