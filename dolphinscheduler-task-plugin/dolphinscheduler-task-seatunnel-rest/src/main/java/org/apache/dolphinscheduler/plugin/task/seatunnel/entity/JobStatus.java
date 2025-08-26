package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import java.util.List;
import java.util.Map;

import lombok.Data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatus extends JobInfo {

    @JsonProperty("jobStatus")
    private String jobStatus;

    @JsonProperty("createTime")
    private String createTime;

    @JsonProperty("jobDag")
    private JobDag jobDag;

    @JsonProperty("metrics")
    private Metrics metrics;

    @JsonProperty("finishTime")
    private String finishTime;

    @JsonProperty("errorMsg")
    private String errorMsg;

    @JsonProperty("envOptions")
    private Map<String, Object> envOptions;

    @JsonProperty("pluginJarsUrls")
    private List<String> pluginJarsUrls;

    @JsonProperty("isStartWithSavePoint")
    private boolean isStartWithSavePoint;

    @Data
    public static class JobDag {

        @JsonProperty("jobId")
        private String jobId;
        @JsonProperty("envOptions")
        private Map<String, String> envOptions;
        @JsonProperty("vertexInfoMap")
        private Map<Long, VertexInfo> vertexInfoMap;
        @JsonProperty("pipelineEdges")
        private Map<Integer, List<Edge>> pipelineEdges;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VertexInfo {

        @JsonProperty("vertexId")
        private Integer vertexId;

        @JsonProperty("type")
        private String type;

        @JsonProperty("vertexName")
        private String vertexName;

        @JsonProperty("tablePaths")
        private List<String> tablePaths;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metrics {

        @JsonProperty("IntermediateQueueSize")
        private String intermediateQueueSize;

        @JsonProperty("SourceReceivedCount")
        private String sourceReceivedCount;

        @JsonProperty("SourceReceivedQPS")
        private String sourceReceivedQPS;

        @JsonProperty("SourceReceivedBytes")
        private String sourceReceivedBytes;

        @JsonProperty("SourceReceivedBytesPerSeconds")
        private String sourceReceivedBytesPerSeconds;

        @JsonProperty("SinkWriteCount")
        private String sinkWriteCount;

        @JsonProperty("SinkWriteQPS")
        private String sinkWriteQPS;

        @JsonProperty("SinkWriteBytes")
        private String sinkWriteBytes;

        @JsonProperty("SinkWriteBytesPerSeconds")
        private String sinkWriteBytesPerSeconds;

        @JsonProperty("TableSourceReceivedCount")
        private Map<String, String> tableSourceReceivedCount;

        @JsonProperty("TableSourceReceivedBytes")
        private Map<String, String> tableSourceReceivedBytes;

        @JsonProperty("TableSourceReceivedBytesPerSeconds")
        private Map<String, String> tableSourceReceivedBytesPerSeconds;

        @JsonProperty("TableSourceReceivedQPS")
        private Map<String, String> tableSourceReceivedQPS;

        @JsonProperty("TableSinkWriteCount")
        private Map<String, String> tableSinkWriteCount;

        @JsonProperty("TableSinkWriteQPS")
        private Map<String, String> tableSinkWriteQPS;

        @JsonProperty("TableSinkWriteBytes")
        private Map<String, String> tableSinkWriteBytes;

        @JsonProperty("TableSinkWriteBytesPerSeconds")
        private Map<String, String> tableSinkWriteBytesPerSeconds;

    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Edge {

        private Long inputVertexId;

        private Long targetVertexId;
    }

}
