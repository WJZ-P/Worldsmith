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

- `com.wjz.worldsmith`: common initialization
- `com.wjz.worldsmith.client`: client-only initialization and future create-world UI
- `com.wjz.worldsmith.datagen`: generated data entrypoint
- `com.wjz.worldsmith.worldgen`: prompt-driven world-generation integration

## Build

```powershell
./gradlew.bat build
```

## Run the development client

```powershell
./gradlew.bat runClient
```
