package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
@Setter
@NoArgsConstructor
@Data
public class JobInfo {

    @JsonProperty("jobId")
    private String jobId;
    @JsonProperty("jobName")
    private String jobName;
}
