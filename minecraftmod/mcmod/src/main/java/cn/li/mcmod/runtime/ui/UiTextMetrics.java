package cn.li.mcmod.runtime.ui;

/**
 * Neutral text measurement contract injected into the layout engine so it
 * can size/wrap/ellipsize text without depending on Minecraft. Each MC
 * version backend supplies one implementation backed by its own font stack
 * (MSDF or vanilla).
 *
 * epoch() MUST change whenever a previously-returned measurement can no
 * longer be trusted (for example: an MSDF font face finishes async loading
 * after text was measured against the vanilla fallback). The layout engine
 * treats an epoch change as a global text-node invalidation — without this,
 * text measured before a font becomes ready stays wrong forever once
 * memoization is in place.
 *
 * Pure interface, no default methods: loop/dispatch logic must live in the
 * engine package, not in the ABI (see verifyPresentationJavaPurity).
 */
public interface UiTextMetrics {
    long epoch();

    float advance(int fontId, String text, float fontSize);

    float lineHeight(int fontId, float fontSize);

    /**
     * Index (exclusive) of the last character of {@code text} that fits
     * within {@code maxWidth} at {@code fontSize}, for word/character wrap.
     * Returns {@code text.length()} when the whole string fits.
     */
    int breakIndex(int fontId, String text, float fontSize, float maxWidth);
}
