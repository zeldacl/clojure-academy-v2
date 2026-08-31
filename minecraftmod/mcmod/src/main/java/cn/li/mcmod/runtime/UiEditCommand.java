package cn.li.mcmod.runtime;

import java.util.Map;

/** Version-neutral structural edit request for a mounted UI. */
public sealed interface UiEditCommand
        permits UiEditCommand.Insert, UiEditCommand.Replace,
                UiEditCommand.Remove, UiEditCommand.Move {
    String targetKey();

    record Insert(String targetKey, String slot, int index, String blueprint,
                  String key, Map<String, Object> props,
                  Map<String, Object> slots) implements UiEditCommand {
        public Insert {
            requireKey(targetKey, "targetKey");
            requireKey(blueprint, "blueprint");
            requireKey(key, "key");
            slot = slot == null ? "" : slot;
            if (index < -1) throw new IllegalArgumentException("index must be -1 or non-negative");
            props = immutableMap(props);
            slots = immutableMap(slots);
        }
    }

    record Replace(String targetKey, String blueprint,
                   Map<String, Object> props,
                   Map<String, Object> slots) implements UiEditCommand {
        public Replace {
            requireKey(targetKey, "targetKey");
            requireKey(blueprint, "blueprint");
            props = immutableMap(props);
            slots = immutableMap(slots);
        }
    }

    record Remove(String targetKey) implements UiEditCommand {
        public Remove { requireKey(targetKey, "targetKey"); }
    }

    record Move(String targetKey, String parentKey, String slot,
                int index) implements UiEditCommand {
        public Move {
            requireKey(targetKey, "targetKey");
            requireKey(parentKey, "parentKey");
            slot = slot == null ? "" : slot;
            if (index < -1) throw new IllegalArgumentException("index must be -1 or non-negative");
        }
    }

    private static void requireKey(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(value);
    }
}
