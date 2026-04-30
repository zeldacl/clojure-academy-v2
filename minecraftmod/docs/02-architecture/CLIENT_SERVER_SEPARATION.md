# Client/Server Code Separation - Implementation Summary

## Completed Changes

### 1. Side Detection System
- **Created**: `forge-1.20.1/src/main/clojure/cn/li/forge1201/side.clj`
- Functions: `client-side?`, `server-side?`, `resolve-client-fn`
- Provides runtime detection of physical client vs dedicated server

### 2. Client-Only Modules
- **Created**: `forge-1.20.1/client/i18n_impl.clj` - Wraps `net.minecraft.client.resources.language.I18n`
- **Created**: `forge-1.20.1/client/render_buffer_impl.clj` - Wraps `RenderType` and `MultiBufferSource`

### 3. Platform Layer Updates
- **Modified**: `forge-1.20.1/platform_impl.clj` - Removed `net.minecraft.client.*` imports, delegates to client modules
- **Modified**: `forge-1.20.1/mod.clj` - Added side detection, removed direct client class usage

### 4. Java Annotations
- **Modified**: `ForgeClientHelper.java` - Added `@OnlyIn(Dist.CLIENT)`
- **Modified**: `CGuiContainerScreen.java` - Added `@OnlyIn(Dist.CLIENT)`

### 5. Documentation
- Added CLIENT-ONLY docstrings to all `*/client/*` namespaces
- Documents that these must be loaded via side-checked `requiring-resolve`

## Architecture Compliance ✅

- ✅ ac/ module: No Minecraft imports
- ✅ ac/ module: No platform imports
- ✅ mcmod/ module: No Minecraft imports
- ✅ mcmod/ module: No platform imports
- ✅ forge-1.20.1/: No direct ac imports
- ✅ mcmod/: No direct ac imports
- ✅ All Java client classes have @OnlyIn annotation
- ✅ Build successful: forge-1.20.1-1.0.0.jar (16MB)

## Key Principles

1. **Dependency chain**: **`ac`** 与 **`forge-1.20.1`** 互不直接依赖；二者均依赖 **`mcmod`**（及需要时的 **`api`**）。Forge 适配层通过 `mcmod` 生命周期与元数据拉起 `ac`，而非在命名空间上 `require` `cn.li.ac.*`。详见 [Runtime_And_DSL_CN.md](Runtime_And_DSL_CN.md)。
2. **Client isolation**: `net.minecraft.client.*` only in platform (`forge-1.20.1`) CLIENT sublayer
3. **Side detection**: All client code loaded via `side/resolve-client-fn`
4. **Runtime separation**: Single JAR works on both client and dedicated server

## Next Steps

To test on dedicated server:
```bash
./gradlew :forge-1.20.1:runServer
```

Verify:
- Server starts without ClassNotFoundException
- No "Client setup phase" in logs
- Blocks/items register correctly
