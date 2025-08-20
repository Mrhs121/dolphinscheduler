package org.apache.dolphinscheduler.plugin.task.seatunnel.exception;

public class SeatunnelRestException extends RuntimeException {

    public SeatunnelRestException() {
        super();
    }

    public SeatunnelRestException(String message) {
        super(message);
    }

    public SeatunnelRestException(String message, Throwable cause) {
        super(message, cause);
    }

}
