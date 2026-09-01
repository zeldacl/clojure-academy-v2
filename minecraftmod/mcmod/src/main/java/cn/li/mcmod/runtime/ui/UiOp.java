package cn.li.mcmod.runtime.ui;

/**
 * Runtime draw-command opcode vocabulary. The declarative .ui.edn primitive
 * set (row/column/stack/absolute/rect/image/...) is compiled down to this
 * small fixed set before it ever reaches a command buffer; the backend
 * dispatches on these integers with a Clojure `case` (tableswitch), never on
 * `instanceof`.
 */
public interface UiOp {
    int RECT = 0;
    int IMAGE = 1;
    int TEXT = 2;
    int NINE = 3;
    int PROGRESS = 4;
    int ITEM = 5;
    int MODEL = 6;
    int GRADIENT = 7;
    /**
     * Polymorphic per-item leaf: the collection item itself names its own
     * kind (quad/image/text/condition/model) and drawing params, resolved
     * at paint time via BindResolver into a CompositeSpec. A real, load-
     * bearing pattern beyond just the combat HUD (developer's condition
     * grid, skill-tree's graph/tag items, tutorial's preview/tag items all
     * need heterogeneous per-item content the static primitive set can't
     * express statically) - kept as a genuine opcode rather than deleted.
     */
    int COMPOSITE = 8;

    int COUNT = 9;
}

