package org.apache.dolphinscheduler.plugin.task.seatunnel;

import static org.apache.dolphinscheduler.plugin.task.api.TaskConstants.EXIT_CODE_FAILURE;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.AbstractRemoteTask;
import org.apache.dolphinscheduler.plugin.task.api.TaskCallBack;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.model.ApplicationInfo;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class SeatunnelRestTask extends AbstractRemoteTask {

    /**
     *
     */
    private SeatunnelRestParameters seatunnelRestParameters;

    private TaskExecutionContext taskExecutionContext;

    private SeatunnelClient seatunnelClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private JobInfo jobInfo;

    /**
     * constructor
     *
     * @param taskExecutionContext taskExecutionContext
     */
    protected SeatunnelRestTask(TaskExecutionContext taskExecutionContext) {
        super(taskExecutionContext);
        this.taskExecutionContext = taskExecutionContext;
        this.seatunnelRestParameters =
                JSONUtils.parseObject(taskExecutionContext.getTaskParams(), SeatunnelRestParameters.class);
        assert seatunnelRestParameters != null;
        this.seatunnelClient = new SeatunnelClient(seatunnelRestParameters.getRestUrl());
    }

    @Override
    public void handle(TaskCallBack taskCallBack) throws TaskException {
        log.info("Starting SeaTunnel REST task with config...");
        if (!seatunnelRestParameters.checkParameters()) {
            throw new TaskException("Invalid Seatunnel REST task parameters.");
        }
        try {
            if (StringUtils.isEmpty(taskExecutionContext.getAppIds())) {
                jobInfo = seatunnelClient.submitJob(
                        seatunnelRestParameters.getJobName(),
                        seatunnelRestParameters.getJobConf());
                log.info("SeaTunnel job submitted, jobId: {}", jobInfo.getJobId());

                // 更新任务实例
                pushAppInfo(taskCallBack, jobInfo.getJobId(), "SUBMITTED", null, null);
            }

            // 开始轮询任务执行的进度信息
            while (true) {
                JobStatus jobStatus = seatunnelClient.getJobStatus(jobInfo.getJobId());
                log.info("Polling Seatunnel status: jobId={}, status={}, metrics={}",
                        jobStatus.getJobId(), jobStatus.getJobStatus(), jobStatus.getMetrics());

                // 每次把最新状态和 metrics 打包进 appIds
                pushAppInfo(taskCallBack, jobStatus.getJobId(), jobStatus.getJobStatus(), jobStatus.getMetrics(),
                        jobStatus.getErrorMsg());

                if (isTerminal(jobStatus.getJobStatus())) {
                    if ("FINISHED".equalsIgnoreCase(jobStatus.getJobStatus())) {
                        pushAppInfo(taskCallBack, jobStatus.getJobId(), jobStatus.getJobStatus(), jobStatus.getMetrics(),
                                jobStatus.getErrorMsg());
                        log.info("Seatunnel job finished successfully, jobId={}", jobStatus.getJobId());
                    } else {
                        log.warn("Seatunnel job end with state={}, jobId={}, error={}",
                                jobStatus.getJobStatus(), jobStatus.getJobId(), jobStatus.getErrorMsg());
                        setExitStatusCode(EXIT_CODE_FAILURE);
                    }
                    break;
                }

                Thread.sleep(Math.max(1000, seatunnelRestParameters.getPollIntervalMs()));
            }

        } catch (Exception e) {
            log.error("Seatunnel REST task failed", e);
            setExitStatusCode(EXIT_CODE_FAILURE);
            throw new TaskException("Seatunnel REST task failed", e);
        }
    }

    private boolean isTerminal(String state) {
        if (state == null)
            return false;
        String s = state.toUpperCase(Locale.ROOT);
        return s.equals("FINISHED") || s.equals("FAILED") || s.equals("CANCELED") || s.equals("CANCELLED");
    }

    /**
     * 把 jobId + 状态 + metrics 打包成 JSON 字符串，放进 appIds
     * 这样不改 DS 表结构，前端拿任务实例信息即可解析绘图
     */
    private void pushAppInfo(TaskCallBack cb, String jobId, String jobStatus,
                             JobStatus.Metrics metrics, String errorMsg) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", jobId);
            payload.put("jobStatus", jobStatus);
            if (errorMsg != null)
                payload.put("errorMsg", errorMsg);
            if (metrics != null)
                payload.put("metrics", metrics);

            ApplicationInfo ai = new ApplicationInfo();
            ai.setAppIds(mapper.writeValueAsString(payload));
            cb.updateRemoteApplicationInfo(taskExecutionContext.getTaskInstanceId(), ai);
        } catch (Exception e) {
            log.warn("pushAppInfo error", e);
        }
    }

    @Override
    public List<String> getApplicationIds() throws TaskException {
        return Collections.emptyList();
    }

    @Override
    public void cancelApplication() throws TaskException {
        if (StringUtils.isEmpty(taskExecutionContext.getAppIds())) {
            return;
        }

        String appIds = taskExecutionContext.getAppIds();
        try {
            Map<String, Object> map = mapper.readValue(appIds, Map.class);
            String jobId = (String) map.get("jobId");

            if (StringUtils.isNotEmpty(jobId)) {
                log.info("Canceling Seatunnel job for taskInstanceId={}", taskExecutionContext.getTaskInstanceId());
                seatunnelClient.stopJob(jobId);
            } else {
                log.warn("JobId is empty or null in appIds: {}", appIds);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse appIds JSON: {}", appIds, e);
            throw new TaskException("Failed to parse application IDs JSON", e);
        } catch (Exception e) {
            log.error("Unexpected error while canceling Seatunnel job for taskInstanceId={}",
                    taskExecutionContext.getTaskInstanceId(), e);
            throw new TaskException("Unexpected error during job cancellation", e);
        }
    }

    @Override
    public void submitApplication() throws TaskException {

    }

    @Override
    public void trackApplicationStatus() throws TaskException {

    }

    @Override
    public AbstractParameters getParameters() {
        return seatunnelRestParameters;
    }
}
