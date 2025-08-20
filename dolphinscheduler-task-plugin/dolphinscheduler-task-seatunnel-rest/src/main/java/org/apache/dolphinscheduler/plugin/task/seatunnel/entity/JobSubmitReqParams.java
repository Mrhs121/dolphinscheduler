package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobSubmitReqParams {

    private String jobName;
    public String jobConfig;
}
