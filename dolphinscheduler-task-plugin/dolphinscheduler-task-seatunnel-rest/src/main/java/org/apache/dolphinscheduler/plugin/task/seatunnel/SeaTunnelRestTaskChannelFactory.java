package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.plugin.task.api.TaskChannel;
import org.apache.dolphinscheduler.plugin.task.api.TaskChannelFactory;
import org.apache.dolphinscheduler.spi.params.base.PluginParams;

import java.util.List;

import com.google.auto.service.AutoService;

@AutoService(SeaTunnelRestTaskChannelFactory.class)
public class SeaTunnelRestTaskChannelFactory implements TaskChannelFactory {

    @Override
    public String getName() {
        return "SEATUNNEL_REST_V2";
    }

    @Override
    public List<PluginParams> getParams() {
        return null;
    }

    @Override
    public TaskChannel create() {
        return new SeaTunnelRestTaskChannel();
    }
}
