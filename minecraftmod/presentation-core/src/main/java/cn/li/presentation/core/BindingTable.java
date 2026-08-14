package cn.li.presentation.core;

/** Numeric binding access used by compiled templates. */
public interface BindingTable {
    Object value(int bindingId);

    default long revision(int bindingId) { return 0L; }

    static BindingTable empty() {
        return new BindingTable() {
            @Override public Object value(int bindingId) { return null; }
        };
    }
}
