package cn.li.mcmod.content.spi;

import java.util.ServiceLoader;

/**
 * Entry point for content bootstrap discovery.
 */
public final class ContentInitBootstraps {

    private ContentInitBootstraps() {
    }

    public static boolean register(String contentId) {
        ServiceLoader<ContentInitBootstrap> loader = ServiceLoader.load(ContentInitBootstrap.class);
        for (ContentInitBootstrap bootstrap : loader) {
            if (bootstrap.contentId().equals(contentId)) {
                bootstrap.register();
                return true;
            }
        }
        return false;
    }
}