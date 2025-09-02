package org.apache.dolphinscheduler.api.service;

import org.apache.dolphinscheduler.api.dto.BootstrapNamespaceResponse;
import org.apache.dolphinscheduler.dao.entity.User;

public interface QDataNamespaceService {

    /**
     *  租户/用户/token 的创建或复用
     *
     * @param operator     当前登录用户
     * @param namespace    租户编码（= tenantCode）
     * @param displayName  租户显示名，可空
     * @param expireAt     失效时间
     * @param resetIfExists 若 true 则重发 token 并确保用户租户绑定
     */
    BootstrapNamespaceResponse bootstrapNamespace(User operator,
                                                  String namespace,
                                                  String displayName,
                                                  String  expireAt,
                                                  Boolean resetIfExists);
}
