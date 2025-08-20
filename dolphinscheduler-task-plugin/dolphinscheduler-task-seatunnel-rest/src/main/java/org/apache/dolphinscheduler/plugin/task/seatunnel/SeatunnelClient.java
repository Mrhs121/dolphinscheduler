package org.apache.dolphinscheduler.plugin.task.seatunnel;

import org.apache.dolphinscheduler.plugin.task.api.TaskPluginException;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobInfo;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStatus;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobStopReqParams;
import org.apache.dolphinscheduler.plugin.task.seatunnel.entity.JobSubmitReqParams;
import org.apache.dolphinscheduler.plugin.task.seatunnel.exception.SeatunnelRestException;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;

public class SeatunnelClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SUBMIT_JOB = "/submit-job";
    private static final String GET_JOB_STATUS = "/job-info/";
    private static final String STOP_JOB = "/stop-job";

    private final String serverUrl;

    public SeatunnelClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * submitJob
     *
     * @param jobName
     * @param jobConfig
     * @return
     * @throws TaskPluginException
     */
    public JobInfo submitJob(String jobName, String jobConfig) throws SeatunnelRestException {
        String submitUrl = serverUrl + SUBMIT_JOB;

        JobSubmitReqParams jobSubmitReqParams = JobSubmitReqParams.builder()
                .jobName(jobName)
                .jobConfig(jobConfig)
                .build();
        return doPost(submitUrl, jobSubmitReqParams, JobInfo.class);
    }

    /**
     * getJobStatus
     *
     * @param jobId
     * @return
     * @throws TaskPluginException
     */
    public JobStatus getJobStatus(String jobId) throws SeatunnelRestException {
        String statusUrl = serverUrl + GET_JOB_STATUS + jobId;

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(statusUrl);

            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(5000)
                    .setSocketTimeout(5000)
                    .build();
            httpGet.setConfig(config);

            HttpResponse response = httpClient.execute(httpGet);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity());

            if (statusCode != HttpStatus.SC_OK) {
                throw new SeatunnelRestException(
                        "Get status failed with status: " + statusCode + ", response: " + responseBody);
            }
            return objectMapper.readValue(responseBody, JobStatus.class);

        } catch (Exception e) {
            throw new SeatunnelRestException("Get job status error", e);
        }
    }

    public void stopJob(String jobId) throws SeatunnelRestException {
        stopJob(jobId, false);
    }

    /**
     * stopJob
     *
     * @param jobId
     * @param isStopWithSavePoint
     * @return
     * @throws SeatunnelRestException
     */
    public JobInfo stopJob(String jobId, boolean isStopWithSavePoint) throws SeatunnelRestException {
        String cancelUrl = serverUrl + STOP_JOB;

        JobStopReqParams jobStopReqParams = JobStopReqParams.builder()
                .jobId(jobId)
                .isStopWithSavePoint(isStopWithSavePoint)
                .build();
        return doPost(cancelUrl, jobStopReqParams, JobInfo.class);
    }

    private <T> T doPost(String serverUrl, Object reqObj, Class<T> clazz) {

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(serverUrl);
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString());

            String jsonBody = objectMapper.writeValueAsString(reqObj);
            httpPost.setEntity(new StringEntity(jsonBody));

            HttpResponse response = httpClient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity());

            if (statusCode != HttpStatus.SC_OK) {
                throw new SeatunnelRestException(
                        "Cancel failed with status: " + statusCode + ", response: " + responseBody);
            }

            return objectMapper.readValue(responseBody, clazz);
        } catch (Exception e) {
            throw new SeatunnelRestException("job error", e);
        }

    }

}
