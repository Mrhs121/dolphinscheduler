package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobLogFile {

    private String node;
    private String logLink;
    private String logName;
}
