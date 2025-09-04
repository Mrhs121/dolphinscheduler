package org.apache.dolphinscheduler.api.service.impl;

import org.apache.dolphinscheduler.api.dto.BootstrapNamespaceResponse;
import org.apache.dolphinscheduler.api.service.AccessTokenService;
import org.apache.dolphinscheduler.api.service.QDataNamespaceService;
import org.apache.dolphinscheduler.api.service.QueueService;
import org.apache.dolphinscheduler.api.service.TenantService;
import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.common.enums.UserType;
import org.apache.dolphinscheduler.dao.entity.AccessToken;
import org.apache.dolphinscheduler.dao.entity.Queue;
import org.apache.dolphinscheduler.dao.entity.Tenant;
import org.apache.dolphinscheduler.dao.entity.User;

import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 创建namespace-tenant,user,token
 */
@Service
@Slf4j
public class QDataNamespaceServiceImpl extends BaseServiceImpl implements QDataNamespaceService {

    private static final String DEFAULT_QUEUE_NAME = "default";
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%^&*";
    private static final int PASSWORD_LENGTH = 12;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private UsersService usersService;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private QueueService queueService;

    @Override
    public BootstrapNamespaceResponse bootstrapNamespace(User operator,
                                                         String namespace,
                                                         String displayName,
                                                         String expireAt,
                                                         Boolean resetIfExists) {
        validateOperatorAndNamespace(operator, namespace);

        boolean reset = Boolean.TRUE.equals(resetIfExists);
        String username = namespace + "_admin";

        Queue defaultQueue = findDefaultQueueOrThrow(operator);

        // 1) 租户：按 tenantCode 精准查或创建
        Tenant tenant = handleTenant(operator, namespace, displayName, defaultQueue);

        // 2) 用户：{namespace}_admin 按用户名查或创建 + 绑定租户
        User user = handleUser(username, defaultQueue, tenant);

        // 3. Token复用or 创建
        AccessToken existingToken = findLatestUnexpiredTokenByUser(operator, user.getId()).orElse(null);
        AccessToken token = handleAccessToken(operator, user.getId(), expireAt, reset, existingToken);

        return buildResponse(namespace, tenant, username, user, token, expireAt, reset, existingToken);
    }

    /**
     * 验证操作者和命名空间参数
     */
    private void validateOperatorAndNamespace(User operator, String namespace) {
        if (operator == null || operator.getUserType() != UserType.ADMIN_USER) {
            throw new IllegalStateException("Only SUPER_ADMIN can bootstrap namespace");
        }
        if (StringUtils.isBlank(namespace)) {
            throw new IllegalArgumentException("namespace cannot be empty");
        }
    }

    /**
     * 处理租户逻辑
     */
    private Tenant handleTenant(User operator, String namespace, String displayName, Queue defaultQueue) {

        Map<String, Object> tenantMap = tenantService.queryByTenantCode(namespace);
        if (tenantMap != null && !tenantMap.isEmpty()) {
            return (Tenant) tenantMap.get("data");
        }
        return createTenant(operator, namespace, displayName, defaultQueue);

    }

    /**
     * 创建租户
     */
    private Tenant createTenant(User operator, String namespace, String displayName, Queue defaultQueue) {
        try {
            return tenantService.createTenant(
                    operator,
                    namespace,
                    defaultQueue.getId(),
                    StringUtils.defaultIfBlank(displayName, namespace));
        } catch (Exception e) {
            log.error("Failed to create tenant: {}", namespace, e);
            throw new IllegalStateException("Failed to create tenant: " + namespace, e);
        }
    }

    /**
     * 处理用户逻辑
     */
    private User handleUser(String username, Queue defaultQueue, Tenant tenant) {
        User user = usersService.queryUser(username);
        if (user == null) {
            return createUser(username, defaultQueue, tenant);
        }
        return user;
    }

    /**
     * 创建用户
     */
    private User createUser(String username, Queue defaultQueue, Tenant tenant) {
        String password = generateRandomPassword();
        User user = usersService.createUser(
                username,
                password,
                username + "@local",
                tenant.getId(),
                "",
                defaultQueue.getQueue(),
                1);
        if (user == null) {
            throw new IllegalStateException("Failed to create user: " + username);
        }
        return user;
    }

    /**
     * 处理访问令牌逻辑
     */
    private AccessToken handleAccessToken(User operator, Integer userId, String expireAt,
                                          boolean reset, AccessToken existingToken) {
        if (reset) {
            deleteAllTokensByUser(operator, userId);
            return createAccessToken(operator, userId, expireAt);
        }
        // 如果有现有令牌且未过期，则重用
        if (existingToken != null &&
                existingToken.getExpireTime() != null &&
                existingToken.getExpireTime().toInstant().isAfter(Instant.now())) {
            return existingToken;
        }
        // 否则创建新令牌
        return createAccessToken(operator, userId, expireAt);
    }

    /**
     * 创建访问令牌
     */
    private AccessToken createAccessToken(User operator, Integer userId, String expireAt) {
        return accessTokenService.createToken(operator, userId, expireAt, "");
    }

    /**
     * 构建响应对象
     */
    private BootstrapNamespaceResponse buildResponse(String namespace, Tenant tenant,
                                                     String username, User user,
                                                     AccessToken token, String expireAt,
                                                     boolean reset, AccessToken existingToken) {
        // 确定操作类型
        Map<String, Object> tenantMap = tenantService.queryByTenantCode(namespace);
        String tenantAction = (tenantMap != null && !tenantMap.isEmpty()) ? "reused" : "created";

        String userAction = (usersService.queryUser(username) != null) ? "reused" : "created";

        String tokenAction;
        if (reset) {
            tokenAction = "reset"; // 重置操作
        } else if (existingToken != null && existingToken.getId().equals(token.getId())) {
            tokenAction = "reused"; // 重用了现有令牌
        } else {
            tokenAction = "created"; // 创建了新令牌
        }
        return BootstrapNamespaceResponse.builder()
                .namespace(namespace)
                .tenantId(tenant.getId())
                .username(username)
                .userId(user.getId())
                .token(token.getToken())
                .expireAt(expireAt)
                .tenantAction(tenantAction)
                .userAction(userAction)
                .tokenAction(tokenAction)
                .build();
    }

    /**
     * 删除该用户下所有 token
     */
    private void deleteAllTokensByUser(User operator, Integer userId) {
        List<AccessToken> tokens = accessTokenService.queryAccessTokenByUser(operator, userId);
        Optional.ofNullable(tokens)
                .orElse(Collections.emptyList())
                .stream()
                .forEach(t -> safelyDeleteAccessToken(operator, t));
    }

    /**
     * 删除token
     */
    private void safelyDeleteAccessToken(User operator, AccessToken token) {
        try {
            accessTokenService.deleteAccessTokenById(operator, token.getId());
        } catch (Exception e) {
            log.warn("Failed to delete access token with ID: {}", token.getId(), e);
        }
    }

    /**
     * 查找用户最新的未过期令牌
     */
    private Optional<AccessToken> findLatestUnexpiredTokenByUser(User user, Integer userId) {
        return Optional.ofNullable(accessTokenService.queryAccessTokenByUser(user, userId))
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .filter(token -> token.getExpireTime() != null &&
                        token.getExpireTime().toInstant().isAfter(Instant.now()))
                .max((t1, t2) -> t2.getExpireTime().compareTo(t1.getExpireTime()));
    }

    /**
     * 查询默认队列
     */
    private Queue findDefaultQueueOrThrow(User operator) {
        return Optional.ofNullable(queueService.queryList(operator))
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .filter(q -> DEFAULT_QUEUE_NAME.equals(q.getQueueName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Default queue '" + DEFAULT_QUEUE_NAME + "' not found, please create it first."));
    }

    /**
     * 生成随机密码
     */
    private String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
