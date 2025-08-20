package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobStopReqParams {

    private String jobId;
    private boolean isStopWithSavePoint;
}
