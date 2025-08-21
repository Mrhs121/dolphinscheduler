package org.apache.dolphinscheduler.plugin.task.seatunnel.exception;

public class SeaTunnelRestException extends RuntimeException {

    public SeaTunnelRestException() {
        super();
    }

    public SeaTunnelRestException(String message) {
        super(message);
    }

    public SeaTunnelRestException(String message, Throwable cause) {
        super(message, cause);
    }

}
