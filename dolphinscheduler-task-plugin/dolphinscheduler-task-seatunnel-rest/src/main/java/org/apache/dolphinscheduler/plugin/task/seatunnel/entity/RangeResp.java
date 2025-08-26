package org.apache.dolphinscheduler.plugin.task.seatunnel.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class RangeResp {

    public final int code;
    public final byte[] body;
    public final Long contentLength;
    public final String etag;

}
