package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.plugin.task.api.TaskPluginException;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobLogFile;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStopReqParams;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobSubmitReqParams;
import org.apache.dolphinscheduler.plugin.task.seatunnel.exception.SeaTunnelRestException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;

@Slf4j
public class SeaTunnelClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SUBMIT_JOB = "/submit-job";
    private static final String GET_JOB_STATUS = "/job-info/";
    private static final String STOP_JOB = "/stop-job";
    private static final String GET_LOG_JOB = "/logs/";

    private final String restUrl;
    private final String authToken;

    public SeaTunnelClient(String restUrl) {

        this(restUrl, null);
    }

    public SeaTunnelClient(String restUrl, String authToken) {

        this.restUrl = StringUtils.removeEnd(restUrl, "/");
        this.authToken = authToken;

    }

    private RequestConfig reqCfg() {

        return RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(30_000)
                .build();
    }

    private void addJsonHeaders(HttpRequestBase req) {

        req.setHeader(HttpHeaders.ACCEPT, MediaType.JSON_UTF_8.toString());
        req.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString());
        if (StringUtils.isNotBlank(authToken)) {
            req.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
        }
    }

    /**
     * submitJob
     *
     * @param jobName
     * @param jobConfig
     * @return
     * @throws TaskPluginException
     */
    public JobInfo submitJob(String jobName, String jobConfig) throws SeaTunnelRestException {

        String submitUrl = restUrl + SUBMIT_JOB;
        JobSubmitReqParams jobSubmitReqParams = JobSubmitReqParams.builder()
                .jobName(jobName)
                .jobConfig(jobConfig)
                .build();
        return doPost(submitUrl, jobSubmitReqParams);
    }

    /**
     * getJobStatus
     *
     * @param jobId
     * @return
     * @throws TaskPluginException
     */
    public JobStatus getJobStatus(String jobId) throws SeaTunnelRestException {

        String statusUrl = restUrl + GET_JOB_STATUS + jobId;
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(statusUrl);
            addJsonHeaders(httpGet);
            httpGet.setConfig(reqCfg());

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (statusCode != HttpStatus.SC_OK) {
                    throw new SeaTunnelRestException(
                            "Get status failed with status: " + statusCode + ", response: " + responseBody);
                }
                return objectMapper.readValue(responseBody, JobStatus.class);
            }
        } catch (Exception e) {
            throw new SeaTunnelRestException("Get job status error", e);
        }
    }

    public void stopJob(String jobId) throws SeaTunnelRestException {

        stopJob(jobId, false);
    }

    /**
     * stopJob
     *
     * @param jobId
     * @param isStopWithSavePoint
     * @return
     * @throws SeaTunnelRestException
     */
    public JobInfo stopJob(String jobId, boolean isStopWithSavePoint) throws SeaTunnelRestException {

        String cancelUrl = restUrl + STOP_JOB;
        JobStopReqParams jobStopReqParams = JobStopReqParams.builder()
                .jobId(jobId)
                .isStopWithSavePoint(isStopWithSavePoint)
                .build();
        return doPost(cancelUrl, jobStopReqParams);
    }

    /**
     * post request
     * @param restUrl
     * @param reqObj
     * @return
     */
    private JobInfo doPost(String restUrl, Object reqObj) {

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(restUrl);
            addJsonHeaders(httpPost);
            httpPost.setConfig(reqCfg());
            httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(reqObj), StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                if (statusCode != HttpStatus.SC_OK) {
                    throw new SeaTunnelRestException(
                            "Seatunnel Task  execute failed with status: " + statusCode + ", response: "
                                    + responseBody);
                }

                JobInfo jobInfo = objectMapper.readValue(responseBody, JobInfo.class);
                if (jobInfo == null || StringUtils.isBlank(jobInfo.getJobId())) {
                    throw new SeaTunnelRestException("request ok but no jobId, body=" + responseBody);
                }
                return jobInfo;
            }

        } catch (Exception e) {
            throw new SeaTunnelRestException("job error", e);
        }

    }

    /**
     * 基于seatunnel log rest 查询
     *
     * @param jobId
     * @return
     */
    public List<JobLogFile> listLogs(String jobId) {

        String logUrl = restUrl + GET_LOG_JOB + ((StringUtils.isBlank(jobId))
                ? "?format=json"
                : "/" + jobId + "?format=json");
        try (CloseableHttpClient hc = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(logUrl);
            addJsonHeaders(httpGet);
            httpGet.setConfig(reqCfg());
            try (CloseableHttpResponse response = hc.execute(httpGet)) {
                int code = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (code != HttpStatus.SC_OK) {
                    throw new SeaTunnelRestException("listLogs failed: " + code + " body=" + body);
                }
                return objectMapper.readValue(body, new TypeReference<List<JobLogFile>>() {
                });
            }
        } catch (Exception e) {
            log.debug("listLogs error url={},err={}", logUrl, e.toString());
            return Collections.emptyList();
        }

    }

    /**
     *  获取SeaTunnel content
     * @param logLink
     * @return
     */
    public String fetchLogFileContent(String logLink) {

        if (StringUtils.isBlank(logLink))
            return "";
        try (CloseableHttpClient hc = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(logLink);
            if (StringUtils.isNotBlank(authToken)) {
                httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
            }
            httpGet.setConfig(reqCfg());
            try (CloseableHttpResponse resp = hc.execute(httpGet)) {
                int statusCode = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (statusCode != HttpStatus.SC_OK) {
                    throw new SeaTunnelRestException("fetchLogFileContent failed: " + statusCode + " body=" + body);
                }
                return body;
            }
        } catch (Exception e) {
            log.debug("fetchLogFileContent error link={} err={}", logLink, e.toString());
            return "";
        }

    }

}
