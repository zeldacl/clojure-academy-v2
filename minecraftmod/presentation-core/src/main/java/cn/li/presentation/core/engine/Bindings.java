package cn.li.presentation.core.engine;

/**
 * Shared bound-attribute resolution helpers used by both {@link LayoutKernel}
 * (text intrinsic sizing, WIDTH/HEIGHT overrides) and {@link PaintKernel}
 * (text content, color, progress ratio, resource refs). All Clojure-shape
 * coercion (item-label fallback chains, get-in path walks, color-map/vector
 * packing) is the {@link BindResolver} implementation's job; these helpers
 * only ever see already-coerced Objects/Numbers/Strings/Integers.
 */
final class Bindings {
    private Bindings() {
    }

    static String text(NodeTable t, BindResolver resolver, int node, Object item) {
        if (resolver != null) {
            Object v = resolver.attribute(node, BindAttr.TEXT, item);
            if (v != null) return String.valueOf(v);
        }
        int idx = t.text[node];
        return idx >= 0 ? t.stringTable[idx] : "";
    }

    static float fontSize(NodeTable t, BindResolver resolver, int node, Object item) {
        if (resolver != null) {
            Object v = resolver.attribute(node, BindAttr.FONT_SIZE, item);
            if (v instanceof Number num) return num.floatValue();
        }
        return t.fontSize[node];
    }

    /**
     * A bound WIDTH/HEIGHT/X/Y override is always a fixed px value. Returns
     * null when unbound -- X/Y legitimately take negative values (e.g. a
     * scroll-shifted or off-screen-animated node), so unlike most of this
     * class's other helpers a sentinel primitive can't distinguish "unbound"
     * from "bound to a negative number."
     */
    static Float numberOverride(BindResolver resolver, int node, int attrSlot, Object item) {
        if (resolver == null) return null;
        Object v = resolver.attribute(node, attrSlot, item);
        return v instanceof Number num ? num.floatValue() : null;
    }

    static int rgba(NodeTable t, BindResolver resolver, int node, Object item) {
        if (resolver != null) {
            Object v = resolver.attribute(node, BindAttr.RGBA, item);
            if (v instanceof Number num) return num.intValue();
        }
        return t.rgba[node];
    }

    /** Progress/radial-progress fill ratio, clamped to [0,1]. Booleans coerce to 0.0/1.0. */
    static float ratio(NodeTable t, BindResolver resolver, int node, Object item) {
        Object v = resolver != null ? resolver.attribute(node, BindAttr.VALUE, item) : null;
        double raw;
        if (v instanceof Boolean bool) raw = bool ? 1.0 : 0.0;
        else if (v instanceof Number num) raw = num.doubleValue();
        else raw = 0.0;
        return (float) Math.max(0.0, Math.min(1.0, raw));
    }

    static int resourceIndex(NodeTable t, BindResolver resolver, int node, Object item) {
        if (resolver != null) {
            Object v = resolver.attribute(node, BindAttr.RESOURCE, item);
            if (v instanceof Integer idx) return idx;
        }
        return t.res[node];
    }
}
