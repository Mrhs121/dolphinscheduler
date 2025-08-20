package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.AbstractTask;
import org.apache.dolphinscheduler.plugin.task.api.TaskChannel;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.api.parameters.ParametersNode;
import org.apache.dolphinscheduler.plugin.task.api.parameters.resource.ResourceParametersHelper;

public class SeatunnelRestTaskChannel implements TaskChannel {

    @Override
    public void cancelApplication(boolean status) {

    }

    @Override
    public AbstractTask createTask(TaskExecutionContext taskRequest) {
        SeatunnelRestParameters seatunnelRestParameters =
                JSONUtils.parseObject(taskRequest.getTaskParams(), SeatunnelRestParameters.class);
        if (seatunnelRestParameters == null || !seatunnelRestParameters.checkParameters()) {
            throw new IllegalArgumentException("Invalid SeatunnelRestParameters");
        }
        return new SeatunnelRestTask(taskRequest);

    }

    @Override
    public AbstractParameters parseParameters(ParametersNode parametersNode) {
        return JSONUtils.parseObject(parametersNode.getTaskParams(), SeatunnelRestParameters.class);
    }

    @Override
    public ResourceParametersHelper getResources(String parameters) {
        return null;
    }
}
