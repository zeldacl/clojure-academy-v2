package cn.li.mcmod.content.spi;

/**
 * Content bootstrap hook discovered via Java ServiceLoader.
 *
 * Implementations should register content init hooks into mcmod lifecycle
 * during {@link #register()}.
 */
public interface ContentInitBootstrap {

    /**
     * Stable content id, e.g. "ac".
     */
    String contentId();

    /**
     * Register content init/activation hooks into mcmod lifecycle.
     */
    void register();
}