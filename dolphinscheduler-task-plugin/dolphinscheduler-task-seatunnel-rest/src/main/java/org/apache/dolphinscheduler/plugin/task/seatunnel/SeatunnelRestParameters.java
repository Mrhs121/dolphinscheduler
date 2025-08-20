package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SeatunnelRestParameters extends AbstractParameters {

    private String jobConf;
    private String jobName;
    private String restUrl;

    /**
     * 轮询间隔（毫秒） 10s
     */
    private long pollIntervalMs = 10_000;

    @Override
    public boolean checkParameters() {
        return StringUtils.isNotEmpty(jobConf)
                && StringUtils.isNotEmpty(jobName)
                && StringUtils.isNotEmpty(restUrl);
    }
}
