package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

public class SeaTunnelConstants {

    public static final String SUBMIT_JOB = "/submit-job";
    public static final String GET_JOB_STATUS = "/job-info/";
    public static final String STOP_JOB = "/stop-job";
    public static final String GET_LOG_JOB = "/logs/";


    public static final String LOCAL_HOST = "localhost:8080";

    public static final String CFG_URL = "seatunnel.rest.url";
    public static final String CFG_TOKEN = "seatunnel.auth.token";
    public static final String CFG_LOG_LIMIT = "seatunnel.log.pull.limit";
    public static final String CFG_POLL_MS = "seatunnel.poll.interval.ms";
    public static final String CFG_FORMAT = "seatunnel.content.format";

    public static final String ENV_URL = "SEATUNNEL_REST_URL";
    public static final String ENV_TOKEN = "SEATUNNEL_AUTH_TOKEN";
    public static final String ENV_LOG_LIMIT = "SEATUNNEL_LOG_PULL_LIMIT";
    public static final String ENV_POLL_MS = "SEATUNNEL_POLL_INTERVAL_MS";
    public static final String ENV_FORMAT = "SEATUNNEL_CONTENT_FORMAT";
}
