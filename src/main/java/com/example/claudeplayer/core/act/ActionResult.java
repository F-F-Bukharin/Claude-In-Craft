package com.example.claudeplayer.core.act;

/** The outcome of one tool call, sent back to the model as a short code; glance adds a PNG ({@code png}, else null). */
public record ActionResult(Status status, String reason, String data, byte[] png) {
    public enum Status { OK, DENIED, FAILED, CANCELLED, SCRAPPED, QUEUED }

    public ActionResult(Status status, String reason, String data) {
        this(status, reason, data, null);
    }

    /** ok plus a picture (glance): the model gets the image and the code. */
    public static ActionResult picture(byte[] png) {
        return new ActionResult(Status.OK, null, null, png);
    }

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

    /** failed plus what got done before it failed (gather's and dig's progress). */
    public static ActionResult failed(String reason, String data) {
        return new ActionResult(Status.FAILED, reason, data);
    }

    public static ActionResult cancelled() {
        return new ActionResult(Status.CANCELLED, null, null);
    }

    /** Dropped because a safety action (level 3) took over; the model may re-plan it. */
    public static ActionResult scrapped() {
        return new ActionResult(Status.SCRAPPED, null, null);
    }

    public static ActionResult queued() {
        return new ActionResult(Status.QUEUED, null, null);
    }

    /** {@code ok}, {@code denied:<reason>}, {@code failed:<reason>}, {@code cancelled}, {@code scrapped} or {@code queued}. */
    public String code() {
        return switch (status) {
            case OK -> "ok";
            case DENIED -> "denied:" + reason;
            case FAILED -> "failed:" + reason;
            case CANCELLED -> "cancelled";
            case SCRAPPED -> "scrapped";
            case QUEUED -> "queued";
        };
    }

    public boolean isError() {
        return status == Status.DENIED || status == Status.FAILED;
    }
}
