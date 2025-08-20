package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import org.apache.dolphinscheduler.plugin.task.api.model.ApplicationInfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeatunnelApplicationInfo extends ApplicationInfo {

    private JobStatus.Metrics metrics;

}
