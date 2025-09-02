package org.apache.dolphinscheduler.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 返回token的实体
 */
@Data
@Builder
public class BootstrapNamespaceResponse {

    private String namespace;

    private Integer tenantId;

    private String username;

    private Integer userId;

    private String token;

    private String expireAt;

    private String tenantAction;

    private String tokenAction;

    private String userAction;
}
