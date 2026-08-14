package cn.li.presentation.core;

import java.util.Arrays;

/** Bounded, typed-at-the-edge action payload. */
public interface ActionPayload {
    int byteSize();

    static ActionPayload empty() {
        return new ActionPayload() {
            @Override public int byteSize() { return 0; }
            @Override public String toString() { return "ActionPayload.empty"; }
        };
    }

    static ActionPayload bytes(byte[] bytes) {
        byte[] copy = bytes == null ? new byte[0] : bytes.clone();
        if (copy.length > 4096) throw new IllegalArgumentException("action payload exceeds 4096 bytes");
        return new Bytes(copy);
    }

    record Bytes(byte[] value) implements ActionPayload {
        public Bytes {
            value = value == null ? new byte[0] : value.clone();
            if (value.length > 4096) throw new IllegalArgumentException("action payload exceeds 4096 bytes");
        }
        @Override public int byteSize() { return value.length; }
        @Override public byte[] value() { return value.clone(); }
        @Override public String toString() { return "ActionPayload.Bytes[" + value.length + "]"; }
    }
}
