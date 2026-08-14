package cn.li.presentation.core;

public record ActionResult(Status status, String message) {
    public enum Status { ACCEPTED, REJECTED, IGNORED }
    public ActionResult {
        status = status == null ? Status.IGNORED : status;
        message = message == null ? "" : message;
    }
    public static ActionResult accepted() { return new ActionResult(Status.ACCEPTED, ""); }
    public static ActionResult rejected(String message) { return new ActionResult(Status.REJECTED, message); }
    public static ActionResult ignored() { return new ActionResult(Status.IGNORED, ""); }
}
