package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

public enum ConfigFormat {

    JSON("json"),
    HOCON("hocon");
    public String v;
    ConfigFormat(String v) {
        this.v = v;
    }
}
