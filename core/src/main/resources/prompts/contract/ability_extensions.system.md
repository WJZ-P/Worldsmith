# Trusted AbilityExtension provider workflow

AbilityScript remains a portable, budgeted source language. Java providers are a separate **trusted JVM extension** mechanism, not a sandbox and not new built-in move types. Installed providers can access host APIs, files and network; their Java execution is not preempted by the script opcode budget. Keep each invocation bounded. Use normal AbilityScript composition before adding native capability code.

## SPI and project

Implement `com.wjz.worldsmith.core.ability.extension.AbilityExtension` in a public concrete Java class with a public no-argument constructor:

```java
AbilityCapabilitySpec spec();
AbilityValue invoke(AbilityHost host, java.util.List<AbilityValue> arguments);
```

`host` is the invocation's native AbilityHost, not a global server singleton. Prefer its registered capabilities for ordinary world access. A native embedder may configure additional compiler/probe classpath entries to compile against Minecraft types; no classpath or local filesystem path is accepted from MCP. The actual `spec()` must equal the declared specification exactly, including argument order, version, result, effect flag and description. The startup loader verifies this before registry installation.

Submit `worldsmith_build_ability_extension` with `project`: `id`, `name`, fresh `requestId`, `entryClasses`, `declaredSpecs` (class name → AbilityCapabilitySpec) and `sources` (relative Java path → source). Use 1..16 providers and 1..16 files, at most 1 MiB total UTF-8 source. Class names and relative paths are validated; traversal, absolute paths, backslashes, case-colliding files and protected core/JDK packages are rejected. Entry classes and declaredSpecs must match one-to-one. A requestId cannot silently change its source/declaration hash.

## Compile and static validation

The service uses the host's bundled ECJ with `-proc:none`, Java target 21, a configured heap limit and a compilation deadline. It does not depend on a system javac. The candidate is packaged deterministically, then inspected by a **separate bounded Java process**. That process calls `Class.forName(name, false, loader)` and checks the interface, visibility, concreteness and public no-argument constructor. It never constructs a provider or runs its static initializers, `spec()` or `invoke()`. Therefore a successful job certifies compilation, artifact integrity and **static ABI only**, not correct native behavior, performance, successful startup or safe code.

Read `worldsmith_get_ability_extension_job` until `READY` or a terminal error. Compiler/probe logs, sourceHash and artifactHash are bounded. `FAILED`, `CANCELLED` and `INTERRUPTED` are not ready candidates. An unavailable standalone host says unavailable rather than inventing a compilation or installation result. A restarted host does not resume arbitrary unfinished processes or retain pending approval grants.

Artifact limits: at most 512 ZIP entries and 8 MiB compressed/uncompressed JAR data. The JAR contains `worldsmith-extension.json` (apiVersion 1, id/name/sourceHash, entryClasses, declaredSpecs, javaTarget 21, validation static_abi_only), canonical `worldsmith-extension-sources.json`, compiled classes and the AbilityExtension ServiceLoader declaration. Source hashes include normalized source and declared metadata, excluding requestId. Artifact SHA-256 covers the exact JAR bytes.

## Independent host-UI installation

Call `worldsmith_request_ability_extension_install` for a READY job. The native UI must display its source/artifact hashes, provider declarations, replacement identity and trusted-code warning. **There is no approve tool or approve parameter.** Drawing/session approval does not apply. Only explicit confirmation in this separate UI calls the host-only approval API bound to the exact sourceHash/artifactHash. Changed source, changed artifact or a changed installed predecessor requires a fresh review.

Confirmed installation writes `<id>.jar` and `<id>.approved.json` under the host-configured `config/worldsmith/ability-extensions`. The receipt binds id, sourceHash and artifactHash to the user's confirmation time. Both files are staged on the destination filesystem and atomically replaced; failure during receipt replacement rolls the JAR back. The prior JAR and receipt are preserved at `backups/<id>/<artifactHash>.*`. No provider is registered into running worlds; a successful install returns `restartRequired=true` and `providerCodeExecuted=false`.

After restart, the native loader must verify the approved receipt against the complete JAR hash **before instantiating any provider**, validate its source/manifest/ServiceLoader metadata, then instantiate and check actual `spec()==declaredSpec`. Constructor or metadata failures are reported rather than treated as loaded capabilities. Only this approved next-start loading may execute provider JVM code. Normal pack loading never compiles or installs providers.

## Rollback and cancellation

`worldsmith_list_ability_extension_backups` lists only verified prior artifacts with their preserved receipts. `worldsmith_prepare_ability_extension_rollback` takes `id`, exact `artifactHash`, and a new `requestId`; it rechecks static ABI and produces another candidate. Request a **new** independent install confirmation for that candidate, then restart. No arbitrary local path, live replacement or approval bypass is supported. `worldsmith_cancel_ability_extension_build` only cancels the candidate/process/pending approval; it does not remove or unload an installed extension.
