package org.apache.dolphinscheduler.plugin.task.seatunnel;

import static org.apache.dolphinscheduler.plugin.task.api.TaskConstants.EXIT_CODE_FAILURE;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.CFG_FORMAT;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.CFG_LOG_LIMIT;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.CFG_POLL_MS;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.CFG_TOKEN;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.CFG_URL;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.ENV_FORMAT;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.ENV_LOG_LIMIT;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.ENV_POLL_MS;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.ENV_TOKEN;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.ENV_URL;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.PropertyUtils;
import org.apache.dolphinscheduler.plugin.task.api.AbstractRemoteTask;
import org.apache.dolphinscheduler.plugin.task.api.TaskCallBack;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.model.ApplicationInfo;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.seatunnel.config.SeaTunnelConfig;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.ConfigFormat;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobLogFile;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.RangeResp;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class SeaTunnelRestTask extends AbstractRemoteTask {

    private final SeaTunnelRestParameters seatunnelRestParameters;

    private final TaskExecutionContext taskExecutionContext;

    private final SeaTunnelClient seatunnelClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private JobInfo jobInfo;

    private final Map<String, Long> fileOffset = new ConcurrentHashMap<>();
    // UTF-8 边界处理：每个文件残留的未成完整字符的字节，下一轮拼上
    private final Map<String, byte[]> fileCarry = new ConcurrentHashMap<>();

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
        resolveConfig();
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
            // 自动/显式判定配置格式
            ConfigFormat format =
                    decideFormatAuto(seatunnelRestParameters.getJobConf(), seatunnelRestParameters.getFormat());
            if (StringUtils.isEmpty(taskExecutionContext.getAppIds())) {

                jobInfo = seatunnelClient.submitJob(
                        defaultIfBlank(seatunnelRestParameters.getJobName(), "seaTunnel_job"),
                        checkConfByFormat(seatunnelRestParameters.getJobConf(), format),
                        format);
                log.info("SeaTunnel job submitted, jobId: {}", jobInfo.getJobId());

                // 更新任务实例
                pushAppInfo(taskCallBack, jobInfo.getJobId(), "SUBMITTED", null, null);
            }

            final int chunkLimit = Math.max(1024, Optional.ofNullable(seatunnelRestParameters.getLogPullLimit())
                    .orElse(65536));

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

    /**
     * 自动识别JSON/HOCON
     * @param conf
     * @param configuredFormatOrNull
     * @return
     */
    private ConfigFormat decideFormatAuto(String conf, String configuredFormatOrNull) {
        // 1) 显式配置优先
        if (StringUtils.isNotBlank(configuredFormatOrNull)) {
            return "hocon".equalsIgnoreCase(configuredFormatOrNull)
                    ? ConfigFormat.HOCON
                    : ConfigFormat.JSON;
        }
        // 2) 自动识别：能被 Jackson 解析且为对象/数组 => JSON；否则 HOCON
        String text = stripBom(StringUtils.defaultString(conf)).trim();
        if (text.isEmpty()) {
            return ConfigFormat.JSON; // 空内容按 JSON 处理，后续由服务端报错
        }
        try {
            char c = text.charAt(0);
            if (c == '{' || c == '[') {
                JsonNode node = mapper.readTree(text);
                if (node != null && (node.isObject() || node.isArray())) {
                    return ConfigFormat.JSON;
                }
            } else {
                // 不是 JSON 常见起手（如 '#'、'//'、字母等），直接判 HOCON
                return ConfigFormat.HOCON;
            }
            // 看起来像 JSON，但结构不对，当作 HOCON
            return ConfigFormat.HOCON;
        } catch (Exception ignore) {
            // Jackson 解析失败 => HOCON（例如带注释、非引号 key）
            return ConfigFormat.HOCON;
        }
    }

    private String checkConfByFormat(String conf, ConfigFormat format) throws JsonProcessingException {
        if (format == ConfigFormat.JSON) {
            mapper.readTree(conf); // JSON 快速校验
        }
        // HOCON 不在本地强校验，直接交给 SeaTunnel 解析
        return conf;
    }

    private static String stripBom(String s) {
        if (s == null)
            return null;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private String mask(String s) {
        if (StringUtils.isBlank(s))
            return s;
        if (s.length() <= 6)
            return "***";
        return s.substring(0, 3) + "****" + s.substring(s.length() - 3);
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
                        Optional.ofNullable(seatunnelRestParameters.getLogPullLimit()).orElse(65536));
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
     *
     * @param logKey 日志的key
     * @param deltaBytes 本次从日志文件增量拉到的一段字节
     * @param prefix 日志打印前缀
     */
    private void printIncrementBytes(String logKey, byte[] deltaBytes, String prefix) {
        if (deltaBytes == null || deltaBytes.length == 0)
            return;

        // 上一次的遗留字节
        byte[] carry = fileCarry.getOrDefault(logKey, new byte[0]);
        // 拼接成完整buf
        byte[] buf = new byte[carry.length + deltaBytes.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(deltaBytes, 0, buf, carry.length, deltaBytes.length);

        int cut = safeUtf8Cut(buf);
        String text = new String(buf, 0, cut, StandardCharsets.UTF_8);

        // 记录从cut -> buf.length剩余字节存入map
        byte[] leftover = Arrays.copyOfRange(buf, cut, buf.length);
        fileCarry.put(logKey, leftover);

        String[] lines = text.split("\\r?\\n", -1);
        for (String line : lines) {
            if (StringUtils.isBlank(line))
                continue;
            // 追加seatunnel log
            log.info("{} {}", prefix, line);
        }

    }

    /**
     * 返回 b 的“最大可完整 UTF-8 前缀”长度（零分配、位运算判断）
     */
    private static int safeUtf8Cut(byte[] b) {
        int n = b.length;
        if (n == 0) return 0;

        // 从末尾回退连续的续字节(10xxxxxx)，最多3个
        int i = n - 1;
        int cont = 0;
        while (i >= 0 && cont < 3 && (b[i] & 0xC0) == 0x80) { // 10xxxxxx
            cont++;
            i--;
        }
        if (cont == 0) {
            // 末尾正好在字符边界（ASCII 或完整多字节）
            return n;
        }
        if (i < 0) {
            // 整个缓冲区结尾都是续字节（没看到起始字节），丢弃这些续字节
            return n - cont;
        }
        int lead = b[i] & 0xFF;
        int need;
        if ((lead & 0x80) == 0x00) {           // 0xxxxxxx (ASCII)
            // 前一位是 ASCII，但后面跟了续字节 => 非法续字节，丢弃续字节
            return n - cont;
        } else if ((lead & 0xE0) == 0xC0) {    // 110xxxxx (需要1个续字节)
            need = 1;
        } else if ((lead & 0xF0) == 0xE0) {    // 1110xxxx (需要2个续字节)
            need = 2;
        } else if ((lead & 0xF8) == 0xF0) {    // 11110xxx (需要3个续字节)
            need = 3;
        } else {
            // 非法起始字节，保守丢弃续字节
            return n - cont;
        }
        // 续字节不足 => 被截断，应裁掉起始字节后的续字节
        // i 为起始字节位置
        return (cont < need) ? i : n;
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

        if (jobLogFileList.isEmpty())
            return;

        final int maxBytes = Math.max(1024, chunkLimit);

        // 3.读取文件内容
        for (JobLogFile file : jobLogFileList) {
            if (file == null || StringUtils.isBlank(file.getLogLink()))
                continue;
            try {
                String logLink = file.getLogLink();
                long offset = fileOffset.getOrDefault(logLink, 0L);
                RangeResp rangeResp = seatunnelClient.fetchLogRange(logLink, offset, maxBytes);
                if (rangeResp == null)
                    continue;

                if (rangeResp.code == 206) {
                    byte[] body = rangeResp.body == null ? new byte[0] : rangeResp.body;
                    if (body.length > 0) {
                        printIncrementBytes(logLink, body, prefix);
                        fileOffset.put(logLink, offset + body.length);
                    }
                } else if (rangeResp.code == 200) {
                    long len = rangeResp.contentLength != null ? rangeResp.contentLength
                            : (rangeResp.body != null ? rangeResp.body.length : 0L);
                    if (len > 0 && len < offset) { // 截断/轮转
                        offset = 0L;
                        fileOffset.put(logLink, 0L);
                    }
                    byte[] body = rangeResp.body == null ? new byte[0] : rangeResp.body;
                    if (len <= 0)
                        len = body.length;
                    long tail = Math.max(0, len - offset);
                    if (tail > 0 && body.length >= tail) {
                        int start = (int) Math.max(0, body.length - tail);
                        byte[] delta = Arrays.copyOfRange(body, start, body.length);
                        printIncrementBytes(logLink, delta, prefix);
                        fileOffset.put(logLink, offset + delta.length); // 推进
                    }
                } else if (rangeResp.code == 416) {
                    // 文件可能被截断/轮转了，重置 offset 和残留 carry
                    fileOffset.put(logLink, 0L);
                    fileCarry.remove(logLink);
                    log.info("Range 416, reset offset to 0 for {}", logLink);
                } else {
                    log.debug("unexpected range code={}, url={}", rangeResp.code, logLink);
                }

            } catch (Exception e) {
                log.debug("read log file failed: {} ->{}", file.getLogLink(), e.toString());
            }
        }
    }

    /**
     * 解析并回填参数
     */
    private void resolveConfig() {
        // 取UI 环境界面配置脚本
        String envScript = null;
        try {
            envScript = taskExecutionContext.getEnvironmentConfig();
        } catch (Throwable ignore) {
            // 某些版本字段名差异可在此回退（如有需要可以打印 taskExecutionContext）
        }
        Map<String, String> envFromUi = parseDsEnvironment(envScript);

        // restUrl
        String url = firstNonBlank(seatunnelRestParameters.getRestUrl(),
                System.getenv(ENV_URL),
                envFromUi.get(ENV_URL),
                SeaTunnelConfig.getString(CFG_URL, ENV_URL, null));
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException(
                    "SeaTunnel REST url is empty. Set it via task param `restUrl`, " +
                            "UI Environment `" + ENV_URL + "`, env `" + ENV_URL + "`, or config `" + CFG_URL + "`.");
        }
        seatunnelRestParameters.setRestUrl(url);

        // token(后期若seatunnel添加可用 先冗余)
        String token = firstNonBlank(
                seatunnelRestParameters.getAuthToken(),
                System.getenv(ENV_TOKEN),
                envFromUi.get(ENV_TOKEN),
                SeaTunnelConfig.getString(CFG_TOKEN, ENV_TOKEN, ""));
        seatunnelRestParameters.setAuthToken(token);

        // logPullLimit
        Integer limit = seatunnelRestParameters.getLogPullLimit();
        if (limit == null)
            limit = SeaTunnelConfig.getInt(CFG_LOG_LIMIT, ENV_LOG_LIMIT, 65536);
        seatunnelRestParameters.setLogPullLimit(Math.max(1024, limit));

        // pollIntervalMs
        long pollMs = seatunnelRestParameters.getPollIntervalMs();
        if (pollMs <= 0)
            pollMs = SeaTunnelConfig.getLong(CFG_POLL_MS, ENV_POLL_MS, 10_000L);
        seatunnelRestParameters.setPollIntervalMs(pollMs);

        // format
        String format = firstNonBlank(
                seatunnelRestParameters.getFormat(),
                envFromUi.get(ENV_FORMAT),
                System.getenv(ENV_FORMAT),
                SeaTunnelConfig.getString(CFG_FORMAT, ENV_FORMAT, "json"));
        seatunnelRestParameters.setFormat(format);

        log.info("Resolved SeaTunnel REST config: url={}, authToken={}, logPullLimit={}, pollIntervalMs={}, format={}",
                url,
                mask(token),
                limit,
                pollMs,
                format);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (StringUtils.isNotBlank(v))
                return v;
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return StringUtils.isBlank(s) ? null : Integer.parseInt(s.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Long parseLongOrNull(String s) {
        try {
            return StringUtils.isBlank(s) ? null : Long.parseLong(s.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 读配置文件
     *
     * @param key
     * @param def
     * @return
     */
    private static String getProp(String key, String def) {
        try {
            return PropertyUtils.getString(key, def);
        } catch (Throwable ignore) {
            return def;
        }
    }

    private static int getPropInt(String key, int def) {
        try {
            return PropertyUtils.getInt(key, def);
        } catch (Throwable ignore) {
            return def;
        }
    }

    private static long getPropLong(String key, long def) {
        try {
            return PropertyUtils.getLong(key, def);
        } catch (Throwable ignore) {
            return def;
        }
    }

    /**
     * 解析 DS「环境管理」里的环境脚本（export KEY=VAL）
     * @param envScript
     * @return
     */
    private Map<String, String> parseDsEnvironment(String envScript) {
        Map<String, String> hashMap = new HashMap<>();
        if (StringUtils.isBlank(envScript))
            return hashMap;
        for (String raw : envScript.split("\\r?\\n")) {
            String line = StringUtils.trimToEmpty(raw);
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            if (line.startsWith("export "))
                line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (eq <= 0)
                continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            hashMap.put(k, v);
        }
        return hashMap;
    }

}
