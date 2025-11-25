# Build Instructions

## Prerequisites

### Java Versions
- **Forge 1.16.5**: Requires Java 8 (JDK 8 or 11)
- **Forge 1.20.1**: Requires Java 17+

Set your `JAVA_HOME` environment variable accordingly, or use Gradle toolchains (configured automatically in build scripts).

### Gradle
Uses Gradle Wrapper (included). No separate Gradle installation needed.

## Build Commands (PowerShell)

### Build Everything
```powershell
# Clean and build all modules
.\gradlew clean
.\gradlew buildAll

# Check generated jars
Get-ChildItem .\build\distributions
```

Expected output in `build/distributions/`:
- `my_mod-1.0.0-forge-1.16.5.jar`
- `my_mod-1.0.0-forge-1.20.1.jar`

### Build Individual Versions
```powershell
# Forge 1.16.5 only
.\gradlew :forge-1.16.5:build

# Forge 1.20.1 only
.\gradlew :forge-1.20.1:build
```

Jars appear in:
- `forge-1.16.5/build/libs/`
- `forge-1.20.1/build/libs/`

## Running Development Clients

### Forge 1.16.5
```powershell
.\gradlew :forge-1.16.5:runClient
```
- First run downloads Minecraft assets (may take time)
- Client launches in `forge-1.16.5/run/client/`
- Use `/give @p my_mod:demo_item` to get demo item
- Use `/give @p my_mod:demo_block` to get demo block

### Forge 1.20.1
```powershell
.\gradlew :forge-1.20.1:runClient
```
- Similar behavior to 1.16.5
- Client launches in `forge-1.20.1/run/client/`

## Troubleshooting

### "Unsupported Java version" Error
Check your Java version:
```powershell
java -version
```
- 1.16.5 needs Java 8 or 11
- 1.20.1 needs Java 17+

### Gradle Daemon Issues
```powershell
.\gradlew --stop
.\gradlew clean
```

### Clojure Compilation Errors
The clojurephant plugin compiles Clojure to JVM bytecode. If namespaces fail to compile:
1. Check syntax in `.clj` files
2. Ensure all `require` dependencies exist
3. Run `.\gradlew :core:build --info` for detailed logs

### Missing Assets
Core assets are in `core/src/main/resources/assets/my_mod/`. Both Forge versions include these via sourceSets configuration.

## Adding New Versions

To support additional Forge versions (e.g., 1.19.2):

1. **Create subproject**: `forge-1.19.2/`
2. **Add to `settings.gradle`**: `include 'forge-1.19.2'`
3. **Add properties** to `gradle.properties`:
   ```properties
   forge_1192_version=...
   minecraft_1192_version=1.19.2
   mappings_1192_channel=official
   mappings_1192_version=1.19.2
   ```
4. **Copy and adapt** `forge-1.16.5/build.gradle` with correct plugin versions
5. **Create Clojure namespaces**: `my-mod.forge1192.*` with multimethod implementations
6. **Create Java entry**: Adapt `MyMod1165.java` for 1.19.2 API changes

## Testing Mods in Game

1. Build jars: `.\gradlew buildAll`
2. Copy from `build/distributions/` to your Minecraft `mods/` folder
3. Launch Minecraft with corresponding Forge version installed
4. Check logs for `[my_mod]` messages indicating Clojure init succeeded

## CI/CD Integration

Example GitHub Actions workflow:
```yaml
- name: Build multi-version Forge mods
  run: |
    ./gradlew clean
    ./gradlew buildAll
- name: Upload artifacts
  uses: actions/upload-artifact@v3
  with:
    name: forge-mods
    path: build/distributions/*.jar
```
