package cn.li.presentation.core;

import java.util.Objects;

/** Build-time resolved action identifier. Runtime code never looks up actions by string. */
public record ActionId(int value) {
    public ActionId {
        if (value < 0) throw new IllegalArgumentException("action id must be non-negative");
    }
}
