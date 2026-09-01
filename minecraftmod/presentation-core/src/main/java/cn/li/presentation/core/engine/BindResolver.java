package cn.li.presentation.core.engine;

/**
 * Callback the control plane (Clojure) supplies to resolve one node's bound
 * attribute against the frame's view-state. This is the engine's only
 * escape hatch into Clojure-shaped data — every other engine class is
 * self-contained and MC/Clojure-free (see verifyPresentationEngineIsolation).
 *
 * Implementations interpret :state-scoped paths against the mount's
 * view-state and :item-scoped paths against {@code item}; that
 * interpretation is entirely the control plane's concern, not the
 * engine's.
 */
public interface BindResolver {
    /**
     * @param node     NodeTable node index (not arena instance — the same
     *                 node may be resolved once per collection item)
     * @param attrSlot one of {@link BindAttr}'s constants
     * @param item     the enclosing collection item, or null outside a
     *                 collection
     * @return the resolved value, or null if this node has no binding for
     *         {@code attrSlot} (caller falls back to a static/style value)
     */
    Object attribute(int node, int attrSlot, Object item);
}
