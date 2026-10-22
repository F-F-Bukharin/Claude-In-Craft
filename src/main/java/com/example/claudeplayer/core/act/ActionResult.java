package com.example.claudeplayer.core.act;

/** The outcome of one tool call, sent back to the model as a short code. */
public record ActionResult(Status status, String reason, String data) {
    public enum Status { OK, DENIED, FAILED, CANCELLED, QUEUED }

    public static ActionResult ok() {
        return new ActionResult(Status.OK, null, null);
    }

    /** ok plus a payload the model asked for (get_state's snapshot). */
    public static ActionResult ok(String data) {
        return new ActionResult(Status.OK, null, data);
    }

    public static ActionResult denied(String reason) {
        return new ActionResult(Status.DENIED, reason, null);
    }

    public static ActionResult failed(String reason) {
        return new ActionResult(Status.FAILED, reason, null);
    }

    public static ActionResult cancelled() {
        return new ActionResult(Status.CANCELLED, null, null);
    }

    public static ActionResult queued() {
        return new ActionResult(Status.QUEUED, null, null);
    }

    /** {@code ok}, {@code denied:<reason>}, {@code failed:<reason>}, {@code cancelled} or {@code queued}. */
    public String code() {
        return switch (status) {
            case OK -> "ok";
            case DENIED -> "denied:" + reason;
            case FAILED -> "failed:" + reason;
            case CANCELLED -> "cancelled";
            case QUEUED -> "queued";
        };
    }

    public boolean isError() {
        return status == Status.DENIED || status == Status.FAILED;
    }
}
