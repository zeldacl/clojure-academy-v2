package cn.li.mcmod.uipojo.runtime;

/**
 * Minimal Java node marker for typed {@link UiRt} collections.
 * Full node API remains {@code cn.li.mcmod.ui.node.INode} (Clojure definterface).
 */
public interface IUiNode {
    int getIdx();
}
