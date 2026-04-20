package cn.li.mcmod.platform.spi;

/**
 * Platform bootstrap hook discovered via Java ServiceLoader.
 *
 * Implementations should avoid expensive work in constructors and perform
 * initialization only inside {@link #initialize()}.
 */
public interface PlatformBootstrap {

    /**
     * Stable platform id, e.g. "forge-1.20.1".
     */
    String platformId();

    /**
     * Perform one-time platform initialization.
     */
    void initialize();
}