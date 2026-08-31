package cn.li.mcmod.runtime;

import java.util.Map;

/** Immutable result of a Presentation structural edit transaction. */
public record UiEditResult(Status status, long compositionRevision,
                           String message, Map<String, Object> details) {
    public enum Status { APPLIED, REJECTED, NOOP }

    public UiEditResult {
        if (status == null) throw new NullPointerException("status");
        if (compositionRevision < 0) throw new IllegalArgumentException("negative composition revision");
        message = message == null ? "" : message;
        details = details == null || details.isEmpty() ? Map.of() : Map.copyOf(details);
    }

    public static UiEditResult applied(long revision) {
        return new UiEditResult(Status.APPLIED, revision, "", Map.of());
    }

    public static UiEditResult noop(long revision) {
        return new UiEditResult(Status.NOOP, revision, "", Map.of());
    }

    public static UiEditResult rejected(String message, Map<String, Object> details) {
        return new UiEditResult(Status.REJECTED, 0L, message, details);
    }
}
