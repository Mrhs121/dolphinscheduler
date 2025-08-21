package org.apache.dolphinscheduler.plugin.task.seatunnel;

import static org.apache.dolphinscheduler.plugin.task.api.TaskConstants.EXIT_CODE_FAILURE;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.AbstractRemoteTask;
import org.apache.dolphinscheduler.plugin.task.api.TaskCallBack;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.model.ApplicationInfo;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobLogFile;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class SeaTunnelRestTask extends AbstractRemoteTask {

    private final SeaTunnelRestParameters seatunnelRestParameters;

    private final TaskExecutionContext taskExecutionContext;

    private final SeaTunnelClient seatunnelClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private JobInfo jobInfo;

    // record logFile length , for incr print
    private final Map<String, Integer> fileReadPos = new ConcurrentHashMap<>();

    /**
     * constructor
     *
     * @param taskExecutionContext taskExecutionContext
     */
    protected SeaTunnelRestTask(TaskExecutionContext taskExecutionContext) {
        super(taskExecutionContext);
        this.taskExecutionContext = taskExecutionContext;
        this.seatunnelRestParameters =
                JSONUtils.parseObject(taskExecutionContext.getTaskParams(), SeaTunnelRestParameters.class);
        if (seatunnelRestParameters == null) {
            throw new IllegalArgumentException("SeaTunnelRestParameters parse failed");
        }
        this.seatunnelClient = new SeaTunnelClient(
                seatunnelRestParameters.getRestUrl(),
                seatunnelRestParameters.getAuthToken());
    }

    @Override
    public void handle(TaskCallBack taskCallBack) throws TaskException {
        log.info("Starting SeaTunnel REST task with config...");
        if (!seatunnelRestParameters.checkParameters()) {
            throw new TaskException("Invalid SeaTunnel REST task parameters.");
        }
        try {
            if (StringUtils.isEmpty(taskExecutionContext.getAppIds())) {
                jobInfo = seatunnelClient.submitJob(
                        defaultIfBlank(seatunnelRestParameters.getJobName(), "seaTunnel_job"),
                        seatunnelRestParameters.getJobConf());
                log.info("SeaTunnel job submitted, jobId: {}", jobInfo.getJobId());

                // 更新任务实例
                pushAppInfo(taskCallBack, jobInfo.getJobId(), "SUBMITTED", null, null);
            }

            final int chunkLimit = Math.max(1024, Optional.ofNullable(seatunnelRestParameters.getLogPullLimit())
                    .orElse(8192));

            // 开始轮询任务执行的进度信息,并记录调度日志信息
            while (true) {
                readIncrementalLogs(jobInfo.getJobId(), chunkLimit, "[SeaTunnel Running:]");
                JobStatus jobStatus = seatunnelClient.getJobStatus(jobInfo.getJobId());
                log.info("Polling SeaTunnel status: jobId={}, status={}, metrics={}",
                        jobStatus.getJobId(), jobStatus.getJobStatus(), jobStatus.getMetrics());

                // 每次把最新状态和 metrics 打包进 appIds
                pushAppInfo(taskCallBack, jobStatus.getJobId(), jobStatus.getJobStatus(), jobStatus.getMetrics(),
                        jobStatus.getErrorMsg());

                if (isTerminal(jobStatus.getJobStatus())) {
                    readIncrementalLogs(jobInfo.getJobId(), chunkLimit, "[SeaTunnel Finished:]");
                    if ("FINISHED".equalsIgnoreCase(jobStatus.getJobStatus())) {
                        pushAppInfo(taskCallBack, jobStatus.getJobId(), jobStatus.getJobStatus(),
                                jobStatus.getMetrics(),
                                jobStatus.getErrorMsg());

                        log.info("SeaTunnel job finished successfully, jobId={}", jobStatus.getJobId());
                    } else {
                        log.warn("SeaTunnel job end with state={}, jobId={}, error={}",
                                jobStatus.getJobStatus(), jobStatus.getJobId(), jobStatus.getErrorMsg());
                        setExitStatusCode(EXIT_CODE_FAILURE);
                    }
                    break;
                }
                Thread.sleep(Math.max(1000, seatunnelRestParameters.getPollIntervalMs()));
            }

        } catch (Exception e) {
            log.error("SeaTunnel REST task failed", e);
            setExitStatusCode(EXIT_CODE_FAILURE);
            throw new TaskException("SeaTunnel REST task failed", e);
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
                log.info("Canceling SeaTunnel job for taskInstanceId={}", taskExecutionContext.getTaskInstanceId());
                seatunnelClient.stopJob(jobId);

                // 日志采集
                int chunkLimit = Math.max(1024,
                        Optional.ofNullable(seatunnelRestParameters.getLogPullLimit()).orElse(8192));
                readIncrementalLogs(jobId, chunkLimit, "[SeaTunnel Cancel:]");
            } else {
                log.warn("JobId is empty or null in appIds: {}", appIds);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse appIds JSON: {}", appIds, e);
            throw new TaskException("Failed to parse application IDs JSON", e);
        } catch (Exception e) {
            log.error("Unexpected error while canceling SeaTunnel job for taskInstanceId={}",
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

    private static String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    /**
     * 打印增量日志
     * @param inc
     * @param chunkSize
     * @param prefix
     */
    private void printIncrement(String inc, int chunkSize, String prefix) {

        if (inc == null || inc.isEmpty())
            return;
        final int size = Math.max(512, chunkSize); // 再做一道下限保护
        for (int start = 0; start < inc.length(); start += size) {
            int end = Math.min(start + size, inc.length());
            String chunk = inc.substring(start, end);
            // 以行维度输出，避免中间断行带来阅读困难
            String[] lines = chunk.split("\\r?\\n");
            for (String line : lines) {
                if (StringUtils.isBlank(line))
                    continue;
                log.info("{} {}", prefix, line);
            }
        }
    }

    /**
     * 读取增量日志信息
     */
    private void readIncrementalLogs(String jobId, int chunkLimit, String prefix) {

        // 1.先去拉取日志列表
        List<JobLogFile> jobLogFiles = seatunnelClient.listLogs(jobId);
        // 2.过滤有效日志文件
        List<JobLogFile> jobLogFileList = Optional.ofNullable(jobLogFiles)
                .orElse(Collections.emptyList())
                .stream()
                .filter(e -> StringUtils.isNotBlank(e.getLogName()))
                .collect(Collectors.toList());

        if (jobLogFileList == null || jobLogFileList.isEmpty())
            return;
        // 3.读取文件内容
        for (JobLogFile file : jobLogFileList) {
            if (file == null || StringUtils.isBlank(file.getLogLink()))
                continue;
            try {
                String wholeContent = seatunnelClient.fetchLogFileContent(file.getLogLink());
                if (wholeContent == null)
                    wholeContent = "";
                // 开始记录日志长度信息
                int old = fileReadPos.getOrDefault(file.getLogLink(), 0);
                int now = wholeContent.length();
                // 有增量内容
                if (now > old) {
                    String inc = wholeContent.substring(old);
                    printIncrement(inc, chunkLimit, prefix);
                    // 游标推进：累加完整增量长度，确保不重复也不丢失
                    fileReadPos.put(file.getLogLink(), old + inc.length());
                } else if (now < old) {
                    fileReadPos.put(file.getLogLink(), now);
                }
            } catch (Exception e) {
                log.debug("read log file failed: {} ->{}", file.getLogLink(), e.toString());
            }
        }
    }
}
