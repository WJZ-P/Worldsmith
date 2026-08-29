# Worldsmith

Worldsmith is being built as a Fabric mod that turns a prompt into a
deterministic Minecraft world-generation blueprint.

## Development baseline

- Minecraft Java Edition `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.158.0+26.2`
- Java `25`
- Gradle `9.5.1`

## Packages

- `core`: Kotlin, Minecraft-version-independent prompt and blueprint pipeline
- `com.wjz.worldsmith`: Java common initialization
- `com.wjz.worldsmith.client`: Java client initialization and future create-world UI
- `com.wjz.worldsmith.datagen`: generated data entrypoint
- `com.wjz.worldsmith.worldgen`: Minecraft 26.2 world-generation integration

The core pipeline currently models:

```text
Player prompt
  -> World Bible expansion
  -> Structure catalog planning
  -> Concurrent structure detail agents
  -> Consistency review
  -> WorldBlueprint JSON
```

See [the prompt pipeline architecture](docs/architecture/prompt-pipeline.md).
See [the portable Worldsmith Pack format](docs/architecture/worldsmith-pack.md).

## Build

```powershell
./gradlew.bat build
```

## Run the development client

```powershell
./gradlew.bat runClient
```
