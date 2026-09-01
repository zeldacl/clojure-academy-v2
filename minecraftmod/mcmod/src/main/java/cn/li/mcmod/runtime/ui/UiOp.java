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

    int COUNT = 8;
}
