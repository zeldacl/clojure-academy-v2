package cn.li.mcmod.content.spi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;

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

    public static Set<String> availableContentIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ServiceLoader<ContentInitBootstrap> loader = ServiceLoader.load(ContentInitBootstrap.class);
        for (ContentInitBootstrap bootstrap : loader) {
            String contentId = bootstrap.contentId();
            if (contentId != null && !contentId.isBlank()) {
                ids.add(contentId);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    public static int registerAll() {
        int registered = 0;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ServiceLoader<ContentInitBootstrap> loader = ServiceLoader.load(ContentInitBootstrap.class);
        for (ContentInitBootstrap bootstrap : loader) {
            String contentId = bootstrap.contentId();
            if (contentId != null && !contentId.isBlank() && seen.add(contentId)) {
                bootstrap.register();
                registered++;
            }
        }
        return registered;
    }
}