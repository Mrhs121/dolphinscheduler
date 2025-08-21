package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SeaTunnelRestParameters extends AbstractParameters {

    private String jobConf;
    private String jobName;
    private String restUrl;
    private String authToken;
    private Integer logPullLimit = 8192;

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
