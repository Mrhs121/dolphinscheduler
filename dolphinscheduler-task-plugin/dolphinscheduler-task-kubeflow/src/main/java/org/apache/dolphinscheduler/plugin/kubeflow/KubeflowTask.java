/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.plugin.kubeflow;

import static org.apache.dolphinscheduler.common.constants.Constants.EMPTY_STRING;
import static org.apache.dolphinscheduler.common.constants.Constants.SLEEP_TIME_MILLIS;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_DRIVER_CORES;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_DRIVER_MEMORY;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_EXECUTOR_CORES;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_EXECUTOR_MEMORY;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_NUM_EXECUTORS;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_SPARK_IMAGE;
import static org.apache.dolphinscheduler.plugin.kubeflow.KubeflowHelper.CONSTANTS.DEFAULT_SPARK_TASK_SA;

import org.apache.dolphinscheduler.common.enums.ProgramType;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.OSUtils;
import org.apache.dolphinscheduler.plugin.task.api.AbstractRemoteTask;
import org.apache.dolphinscheduler.plugin.task.api.TaskConstants;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;
import org.apache.dolphinscheduler.plugin.task.api.utils.LogUtils;
import org.apache.dolphinscheduler.plugin.task.api.utils.ParameterUtils;
import org.apache.dolphinscheduler.plugin.task.api.utils.ProcessUtils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.dsl.LogWatch;

@Slf4j
public class KubeflowTask extends AbstractRemoteTask {

    private final TaskExecutionContext taskExecutionContext;
    protected KubeflowHelper kubeflowHelper;
    private KubeflowParameters kubeflowParameters;
    private Path clusterYAMLPath;
    protected boolean podLogOutputIsFinished = false;
    protected boolean processLogOutputIsSuccess = false;
    private Path yamlPath;
    protected Future<?> podLogOutputFuture;
    protected Future<?> taskOutputFuture;
    protected LinkedBlockingQueue<String> logBuffer;
    protected Consumer<LinkedBlockingQueue<String>> logHandler;
    public KubeflowTask(TaskExecutionContext taskExecutionContext) {
        super(taskExecutionContext);
        this.taskExecutionContext = taskExecutionContext;
        this.logBuffer = new LinkedBlockingQueue<>();
        this.logBuffer.add(EMPTY_STRING);
        this.logHandler = this::logHandle;
    }

    /**
     * log handle
     *
     * @param logs log list
     */
    public void logHandle(LinkedBlockingQueue<String> logs) {

        StringJoiner joiner = new StringJoiner("\n\t");
        while (!logs.isEmpty()) {
            joiner.add(logs.poll());
        }
        log.info(" -> {}", joiner);
    }
    @Override
    public void init() throws TaskException {
        kubeflowParameters = JSONUtils.parseObject(taskExecutionContext.getTaskParams(), KubeflowParameters.class);
        log.info("Initialize Kubeflow task params {}", taskExecutionContext.getTaskParams());

        kubeflowParameters.setClusterYAML(taskExecutionContext.getK8sTaskExecutionContext().getConfigYaml());
        if (!kubeflowParameters.checkParameters()) {
            throw new TaskException("Kubeflow task params is not valid");
        }

        writeFiles();
        kubeflowHelper = new KubeflowHelper(clusterYAMLPath.toString());
    }

    @Override
    public void submitApplication() throws TaskException {
        String command = kubeflowHelper.buildSubmitCommand(yamlPath.toString());
        log.info("Kubeflow task submit command: \n{}", command);
        String message = runCommand(command);
        log.info("Kubeflow task submit result: \n{}", message);

        KubeflowHelper.ApplicationIds applicationIds = new KubeflowHelper.ApplicationIds();
        applicationIds.setAlreadySubmitted(true);
        setAppIds(JSONUtils.toJsonString(applicationIds));

        // ------------------- Collect Driver Pod Logs -------------------
        if (kubeflowParameters.getProgramType() == ProgramType.SQL) {
            collectPodLogIfNeeded();
            ExecutorService parseProcessOutputExecutorService = ThreadUtils
                    .newSingleDaemonScheduledExecutorService(
                            "TaskInstanceLogOutput-thread-" + taskRequest.getTaskName());
            taskOutputFuture = parseProcessOutputExecutorService.submit(() -> {
                try {
                    LogUtils.setTaskInstanceLogFullPathMDC(taskRequest.getLogPath());
                    while (logBuffer.size() > 1 || !podLogOutputIsFinished) {
                        if (logBuffer.size() > 1) {
                            logHandler.accept(logBuffer);
                            logBuffer.clear();
                            logBuffer.add(EMPTY_STRING);
                        } else {
                            Thread.sleep(TaskConstants.DEFAULT_LOG_FLUSH_INTERVAL);
                        }
                    }
                } catch (Exception e) {
                    log.error("Output task log error", e);
                } finally {
                    LogUtils.removeTaskInstanceLogFullPathMDC();
                }
            });
            parseProcessOutputExecutorService.shutdown();

            if (taskOutputFuture != null) {
                try {
                    // Wait the task log process finished.
                    taskOutputFuture.get();
                } catch (ExecutionException e) {
                    log.error("Handle task log error", e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            if (podLogOutputFuture != null) {
                try {
                    // Wait kubernetes pod log collection finished
                    podLogOutputFuture.get();
                    // delete pod after successful execution and log collection
                    ProcessUtils.deletePod(taskRequest, getUniquePodAppName());
                } catch (ExecutionException | InterruptedException e) {
                    log.error("Handle pod log error", e);
                }
            }
        }

    }

    private void collectPodLogIfNeeded() {
        if (null == taskRequest.getK8sTaskExecutionContext()) {
            podLogOutputIsFinished = true;
            return;
        }

        ExecutorService collectPodLogExecutorService = ThreadUtils
                .newSingleDaemonScheduledExecutorService("CollectPodLogOutput-thread-" + taskRequest.getTaskName());

        podLogOutputFuture = collectPodLogExecutorService.submit(() -> {
            // wait for launching (driver) pod
            ThreadUtils.sleep(SLEEP_TIME_MILLIS * 5L);
            String driverPodLabel = getUniquePodAppName();
            try (
                    LogWatch watcher = ProcessUtils.getPodLogWatcher(taskRequest.getK8sTaskExecutionContext(),
                            driverPodLabel, "")) {
                if (watcher == null) {
                    throw new RuntimeException("The driver pod does not exist.");
                } else {
                    String line;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(watcher.getOutput()))) {
                        while ((line = reader.readLine()) != null) {
                            logBuffer.add(String.format("[kubeflow-spark-driver-pod-%s]: %s", taskRequest.getTaskName(),
                                    line));
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                podLogOutputIsFinished = true;
            }

        });

        collectPodLogExecutorService.shutdown();
    }

    /**
     * keep checking application status
     *
     * @throws TaskException
     */
    @Override
    public void trackApplicationStatus() throws TaskException {
        String command = kubeflowHelper.buildGetCommand(yamlPath.toString());
        log.info("Kubeflow task get command: \n{}", command);
        do {
            ThreadUtils.sleep(KubeflowHelper.CONSTANTS.TRACK_INTERVAL);
            String message = runCommand(command);
            String phase = kubeflowHelper.parseGetMessage(message);
            if (KubeflowHelper.STATUS.FAILED_SET.contains(phase)) {
                exitStatusCode = TaskConstants.EXIT_CODE_FAILURE;
                log.info("Kubeflow task get Failed result: \n{}", message);
                break;
            } else if (KubeflowHelper.STATUS.SUCCESS_SET.contains(phase)) {
                exitStatusCode = TaskConstants.EXIT_CODE_SUCCESS;
                log.info("Kubeflow task get Succeeded result: \n{}", message);
                break;
            }
        } while (true);
    }

    @Override
    public void cancelApplication() throws TaskException {
        String command = kubeflowHelper.buildDeleteCommand(yamlPath.toString());
        log.info("Kubeflow task delete command: \n{}", command);
        String message = runCommand(command);
        log.info("Kubeflow task delete result: \n{}", message);
        exitStatusCode = TaskConstants.EXIT_CODE_KILL;
    }

    protected String runCommand(String command) {
        try {
            exitStatusCode = TaskConstants.EXIT_CODE_SUCCESS;
            return OSUtils.exeShell(new String[]{"sh", "-c", command});
        } catch (Exception e) {
            exitStatusCode = TaskConstants.EXIT_CODE_FAILURE;
            throw new TaskException("Kubeflow task submit command failed", e);
        }
    }

    @Override
    public List<String> getApplicationIds() throws TaskException {
        return Collections.emptyList();
    }

    public String buildSparkSqlYaml(String yamlContent) throws JsonProcessingException {
        String appName = getUniquePodAppName();
        String sparkDriverLogName = String.format("%s.log", appName);
        final String dsTaskLogPath = Paths.get(taskExecutionContext.getLogPath()).getParent().toString();
        log.info("Spark driver unique pod app name is {}", appName);
        int driverCores =
                kubeflowParameters.getDriverCores() == 0 ? DEFAULT_DRIVER_CORES : kubeflowParameters.getDriverCores();
        int executorCores = kubeflowParameters.getExecutorCores() == 0 ? DEFAULT_EXECUTOR_CORES
                : kubeflowParameters.getExecutorCores();
        int numExecutors = kubeflowParameters.getNumExecutors() == 0 ? DEFAULT_NUM_EXECUTORS
                : kubeflowParameters.getNumExecutors();
        String executorMemory =
                kubeflowParameters.getExecutorMemory() == null ? DEFAULT_EXECUTOR_MEMORY
                        : kubeflowParameters.getExecutorMemory();
        String driverMemory =
                kubeflowParameters.getDriverMemory() == null ? DEFAULT_DRIVER_MEMORY
                        : kubeflowParameters.getDriverMemory();
        String namespace = taskExecutionContext.getK8sTaskExecutionContext().getNamespace();
        String sql = yamlContent;
        String image = DEFAULT_SPARK_IMAGE;
        String serviceAccount = DEFAULT_SPARK_TASK_SA;

        // spark sql yaml content can be json format, such as:
        // {
        // "sql" :"INSERT INTO data_platform.ods_test_wh SELECT * FROM ori.t_ds_audit_log; ",
        // "image" : "huangsheng/spark:3.5.5-mysql-pg-ping",
        // "sa": "spark-account"
        // }

        try {
            JsonNode jsonNodes = JSONUtils.parseObject(yamlContent);
            if (jsonNodes.has("sql")) {
                sql = jsonNodes.get("sql").asText();
            }
            if (jsonNodes.has("image")) {
                image = jsonNodes.get("image").asText();
            }
            if (jsonNodes.has("sa")) {
                serviceAccount = jsonNodes.get("sa").asText();
            }
            log.info("Use custom pod configs image:{}, sa:{}", image, serviceAccount);
        } catch (Exception e) {
            log.info("The yaml content is not a json, fallback to simple text");
        }
        // TODO: the mount path of the external jar needs to be restructured
        List<String> jars = new ArrayList<>();
        List<KubeflowParameters.Udfs.UDFInfo> sparkUdfs = new ArrayList<>();
        if (StringUtils.isNotEmpty(kubeflowParameters.getSparkUdfs())) {
            KubeflowParameters.Udfs udf =
                    JSONUtils.parseObject(kubeflowParameters.getSparkUdfs(), KubeflowParameters.Udfs.class);
            for (KubeflowParameters.Udfs.UDFInfo udfInfo : udf.getUdfs()) {
                jars.add(udfInfo.getJarPath());
                KubeflowParameters.Udfs.UDFInfo sparkUdf = new KubeflowParameters.Udfs.UDFInfo();
                sparkUdf.setFuncName(udfInfo.getFuncName());
                sparkUdf.setClassName(udfInfo.getClassName());
                sparkUdfs.add(sparkUdf);
            }
        }

        String sparkSqlTaskArguments = kubeflowParameters.convertDatasource(sql, sparkUdfs);

        // Todo refactor yaml template
        try {
            InputStream inputStream = KubeflowTask.class.getResourceAsStream("/spark-sql-operator-template.yaml");
            String template = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            return template
                    .replace("${APP_NAME}", appName)
                    .replace("${NAMESPACE}", namespace)
                    .replace("${ARGUMENTS}", sparkSqlTaskArguments)
                    .replace("${SPARK_LOG_FILE_NAME}", sparkDriverLogName)
                    .replace("${DS_TASK_LOG_PATH}", dsTaskLogPath)
                    .replace("${DRIVER_LABEL}", appName)
                    .replace("${DRIVER_CORES}", String.valueOf(driverCores))
                    .replace("${DRIVER_MEMORY}", driverMemory)
                    .replace("${EXECUTOR_CORES}", String.valueOf(executorCores))
                    .replace("${EXECUTOR_MEMORY}", executorMemory)
                    .replace("${NUM_EXECUTORS}", String.valueOf(numExecutors))
                    .replace("${IMAGE}", image)
                    .replace("${SERVICE_ACCOUNT}", serviceAccount)
                    .replace("${JARS}", jars.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private String getUniquePodAppName() {
        return String.format("%s-%s-%s-%s-%s",
                KubeflowHelper.CONSTANTS.SPARK_OPERATOR_SQL_TASK_NAME_PREFIX,
                taskExecutionContext.getProcessDefineCode(),
                taskExecutionContext.getProcessDefineVersion(),
                taskExecutionContext.getProcessInstanceId(),
                taskExecutionContext.getTaskInstanceId());
    }
    public void writeFiles() {
        String yamlContent = kubeflowParameters.getYamlContent();
        String clusterYAML = kubeflowParameters.getClusterYAML();

        Map<String, Property> paramsMap = taskExecutionContext.getPrepareParamsMap();
        yamlContent = ParameterUtils.convertParameterPlaceholders(yamlContent, ParameterUtils.convert(paramsMap));

        yamlPath = Paths.get(taskExecutionContext.getExecutePath(), KubeflowHelper.CONSTANTS.YAML_FILE_PATH);
        clusterYAMLPath =
                Paths.get(taskExecutionContext.getExecutePath(), KubeflowHelper.CONSTANTS.CLUSTER_CONFIG_PATH);

        try {
            if (kubeflowParameters.getProgramType() == ProgramType.SQL) {
                yamlContent = buildSparkSqlYaml(yamlContent);
            }
            log.info("Kubeflow task yaml content: \n{}", yamlContent);
            Files.write(yamlPath, yamlContent.getBytes(), StandardOpenOption.CREATE);
            Files.write(clusterYAMLPath, clusterYAML.getBytes(), StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new TaskException("Kubeflow task write yaml file failed", e);
        }
    }

    @Override
    public KubeflowParameters getParameters() {
        return kubeflowParameters;
    }
}
