package cn.li.fabric1201.bridge;

import java.util.function.Consumer;

/**
 * Bridge for registering Fabric config event listeners.
 *
 * Fabric doesn't have a first-class config event system like Forge's ModConfigEvent.
 * Instead, config updates are typically handled via direct reading or custom event systems.
 * This bridge provides a stub for API compatibility with Forge's ConfigEventBridge.
 */
public final class ConfigEventBridge {
    private ConfigEventBridge() {}

    /**
     * Stub implementation for Fabric config event registration.
     *
     * In Fabric, config changes are typically detected by periodically re-reading config files
     * or through custom ServerTickEvents. This method accepts a listener for potential future use.
     *
     * @param listener Consumer that receives config events (Loading/Reloading)
     */
    public static void addConfigListeners(Consumer<String> listener) {
        // Fabric doesn't have built-in config event bus like Forge.
        // Applications should implement their own config reload detection if needed.
        // This is a placeholder for API compatibility.
    }
}
