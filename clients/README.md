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
runtime-windows.patch    the local runtime changes a Windows build needs
```

## Build and run

JDK 22 and the wrapper. `./gradlew` is committed so no global Gradle is needed.

```
cd clients
./gradlew :windows:run          # opens the window
./gradlew :windows:test         # the gameplay conformance check
```

`win-audio` builds with Cargo: `cargo run --release --manifest-path win-audio/Cargo.toml`.

## Versions

| What | Version |
| --- | --- |
| Gradle | 9.8.2 (the wrapper) |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Compose Material 3 | 1.9.0 |
| JNA | 5.17.0 |
| JDK | 22, the toolchain every module targets |

## The runtime core

`windows/src/main/kotlin/uniffi/ensomi_runtime_ffi` is generated, not written: it is the
Kotlin binding of the Rust core in a [runtime](https://github.com/ensomi-labs/runtime)
checkout beside this repository. The committed copy lets the client build without a Rust
toolchain; regenerate it after a runtime change:

```
cd ../../runtime                     # the runtime checkout, beside the ensomi checkout
git apply ../ensomi/clients/runtime-windows.patch
cargo build --release -p ensomi-runtime-ffi --target x86_64-pc-windows-msvc
cargo run --release -p ensomi-runtime-ffi --features bindgen --bin uniffi-bindgen -- \
  generate --library target/x86_64-pc-windows-msvc/release/ensomi_runtime_ffi.dll \
  --language kotlin --out-dir ../ensomi/clients/windows/src/main/kotlin --no-format
cp target/x86_64-pc-windows-msvc/release/ensomi_runtime_ffi.dll \
   ../ensomi/clients/windows/libs/
```

`runtime-windows.patch` holds two changes that belong upstream: `recording.rs` reads a
file at an offset through the Windows API instead of the Unix one, and `generate-ort`
loads ONNX Runtime at run time (`load-dynamic`) instead of downloading it while building.
Without the second, the build wants `cdn.pyke.io`, which this network cannot reach.

After generating, one edit is needed: the generator's errors carry a field named
`message`, which clashes with Kotlin's `Throwable.message` in the generated exception
classes. Rename the two constructor properties and the two interpolations to
`messageText`; `./gradlew :windows:test` fails to compile the binding otherwise.

`windows/libs/` is not committed, so the test needs the library copied there. When the
generator runs it also needs ONNX Runtime: point `ORT_DYLIB_PATH` at an `onnxruntime.dll`
of a matching version (the Python wheel for Windows carries one).

## Why the mirrors come first

`settings.gradle.kts` lists `maven.aliyun.com` before Maven Central. The network this
project is developed on cannot reach `repo.maven.apache.org`, and the canonical
repositories remain listed after the mirrors, so a machine that can reach them still
resolves anything the mirrors lack. Moving the canonical repositories first is a
two-line change, not a restructure.

## What is still missing

- The window is a placeholder: the workbench, the library and the play screens come
  next, in the order the screen specification gives.
- One conformance case runs; the rest of `conformance/gameplay` needs a JSON reader in
  the test module.
- The clock in `win-audio` is not bound to Kotlin yet.
- Android has no module yet.
