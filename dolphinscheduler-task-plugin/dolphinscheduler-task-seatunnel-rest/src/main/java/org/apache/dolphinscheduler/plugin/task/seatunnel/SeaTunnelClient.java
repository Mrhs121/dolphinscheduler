package org.apache.dolphinscheduler.plugin.task.seatunnel;

import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.GET_JOB_STATUS;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.GET_LOG_JOB;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.STOP_JOB;
import static org.apache.dolphinscheduler.plugin.task.seatunnel.entity.SeaTunnelConstants.SUBMIT_JOB;

import org.apache.dolphinscheduler.plugin.task.api.TaskPluginException;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.ConfigFormat;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobLogFile;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStopReqParams;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.RangeResp;
import org.apache.dolphinscheduler.plugin.task.seatunnel.exception.SeaTunnelRestException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;

@Slf4j
public class SeaTunnelClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String restUrl;
    private final String authToken;

    public SeaTunnelClient(String restUrl) {

        this(restUrl, null);
    }

    public SeaTunnelClient(String restUrl, String authToken) {

        this.restUrl = StringUtils.removeEnd(restUrl, "/");
        this.authToken = authToken;

    }

    public String getRestUrl() {
        return restUrl;
    }

    private RequestConfig reqCfg() {

        return RequestConfig.custom()
                .setConnectTimeout(10_000) // 连接超时：10s
                .setConnectionRequestTimeout(10_000) // 从连接池取连接超时：10s
                .setSocketTimeout(30_000) // 读超时：30s
                .build();
    }

    private void addJsonHeaders(HttpRequestBase req) {

        req.setHeader(HttpHeaders.ACCEPT, MediaType.JSON_UTF_8.toString());
        req.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString());
        if (StringUtils.isNotBlank(authToken)) {
            req.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
        }
    }

    private void addAuthOnly(HttpRequestBase req) {
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
    public JobInfo submitJob(String jobName, String jobConfig) throws SeaTunnelRestException, URISyntaxException {

        return submitJob(jobName, jobConfig, ConfigFormat.JSON);
    }

    /** 新增：支持 JSON/HOCON */
    public JobInfo submitJob(String jobName, String jobConfig,
                             ConfigFormat format) throws SeaTunnelRestException, URISyntaxException {

        URIBuilder ub = new URIBuilder(restUrl + SUBMIT_JOB);
        if (StringUtils.isNotBlank(jobName)) {
            ub.addParameter("jobName", jobName);
        }
        ub.addParameter("format", format == null ? ConfigFormat.JSON.v : format.v);

        URI url = ub.build();
        return doPost(url, jobConfig, format);
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

    public void stopJob(String jobId) throws SeaTunnelRestException, JsonProcessingException {

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
    public JobInfo stopJob(String jobId,
                           boolean isStopWithSavePoint) throws SeaTunnelRestException, JsonProcessingException {

        String cancelUrl = restUrl + STOP_JOB;
        JobStopReqParams jobStopReqParams = JobStopReqParams.builder()
                .jobId(jobId)
                .isStopWithSavePoint(isStopWithSavePoint)
                .build();
        String jobStopJson = objectMapper.writeValueAsString(jobStopReqParams);
        return doPost(cancelUrl, jobStopJson);
    }

    /**
     * post request
     * @param restUrl
     * @param body
     * @return
     */
    private JobInfo doPost(URI restUrl, String body) {

        return doPost(restUrl, body, ConfigFormat.JSON);
    }

    private JobInfo doPost(URI restUrl, String body, ConfigFormat format) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(restUrl);
            if (format == ConfigFormat.HOCON) {
                httpPost.setHeader(HttpHeaders.ACCEPT, MediaType.JSON_UTF_8.toString());
                httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8");
                addAuthOnly(httpPost);
            } else {
                addJsonHeaders(httpPost);
            }
            httpPost.setConfig(reqCfg());
            httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (statusCode != HttpStatus.SC_OK) {
                    throw new SeaTunnelRestException(
                            "Seatunnel Task execute failed with status: " + statusCode + ", response: " + responseBody);
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

    /**
     * 按Range 拉取一段
     * @param logLink
     * @param start
     * @param maxBytes
     * @return
     */
    public RangeResp fetchLogRange(String logLink, long start, int maxBytes) {
        if (StringUtils.isBlank(logLink))
            return new RangeResp(400, new byte[0], 0L, null);
        try (CloseableHttpClient hc = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(logLink);
            addAuthOnly(get);
            get.setConfig(reqCfg());
            get.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            if (start > 0 || maxBytes > 0) {
                // 结束位置
                long end = (maxBytes > 0) ? (start + Math.max(1, maxBytes) - 1) : -1;
                // 构造range请求头
                String rangeVal = (end >= start) ? ("bytes=" + start + "-" + end) : ("bytes=" + start + "-");
                get.setHeader("Range", rangeVal);
            }
            try (CloseableHttpResponse resp = hc.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                String cl = resp.getFirstHeader(HttpHeaders.CONTENT_LENGTH) != null
                        ? resp.getFirstHeader(HttpHeaders.CONTENT_LENGTH).getValue()
                        : null;
                Long contentLength = null;
                try {
                    contentLength = cl == null ? null : Long.parseLong(cl);
                } catch (Exception ignore) {
                }
                String etag = resp.getFirstHeader(HttpHeaders.ETAG) != null
                        ? resp.getFirstHeader(HttpHeaders.ETAG).getValue()
                        : null;

                byte[] body;
                try (
                        InputStream in = resp.getEntity() != null ? resp.getEntity().getContent() : null;
                        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    if (in != null) {
                        byte[] buf = new byte[65536];
                        int r;
                        // 一次写入量 r
                        while ((r = in.read(buf)) != -1)
                            baos.write(buf, 0, r);
                    }
                    body = baos.toByteArray();
                }
                return new RangeResp(code, body, contentLength, etag);
            }
        } catch (Exception e) {
            log.debug("fetchLogRange error link={}, start={}, maxBytes={}, err={}", logLink, start, maxBytes,
                    e.toString());
            return new RangeResp(500, new byte[0], null, null);
        }
    }

    private static URI toUri(String urlStr) {
        try {
            return new URI(StringUtils.trim(urlStr));
        } catch (Exception e) {
            throw new SeaTunnelRestException("Invalid URL string: " + urlStr, e);
        }
    }

    private static URI toUri(URL url) {
        try {
            // URL#toURI 可能抛 URISyntaxException，这里包一层
            return url.toURI();
        } catch (Exception e) {
            throw new SeaTunnelRestException("Invalid URL: " + url, e);
        }
    }

    private JobInfo doPost(String urlStr, String jobJson) {
        return doPost(toUri(urlStr), jobJson);
    }

    // 2) 提供 URL 版本：内部转成 URI
    private JobInfo doPost(URL url, String jobJson) {
        return doPost(toUri(url), jobJson);
    }

}
