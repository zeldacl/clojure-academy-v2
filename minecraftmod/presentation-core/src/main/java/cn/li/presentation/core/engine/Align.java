package cn.li.presentation.core.engine;

/** Cross-axis alignment: {@code alignItems} default on a box, {@code alignSelf} per child override (-1 = inherit). */
public interface Align {
    int INHERIT = -1;
    int START = 0;
    int CENTER = 1;
    int END = 2;
    int STRETCH = 3;
}
