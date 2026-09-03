package cn.li.presentation.core.engine;

/** Attribute slots a node's {@code :bind} map may resolve through {@link BindResolver}. */
public interface BindAttr {
    int VISIBLE = 0;
    int TEXT = 1;
    int VALUE = 2;
    int RGBA = 3;
    int ITEMS = 4;
    int X = 5;
    int Y = 6;
    int WIDTH = 7;
    int HEIGHT = 8;
    int FONT_SIZE = 9;
    int RESOURCE = 10;
    /** Resolves to a {@link CompositeSpec} for a UiOp.COMPOSITE node. */
    int COMPOSITE = 11;
}
