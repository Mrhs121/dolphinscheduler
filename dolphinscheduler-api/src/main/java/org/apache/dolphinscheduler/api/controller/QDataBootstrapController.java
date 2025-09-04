package org.apache.dolphinscheduler.api.controller;

import static org.apache.dolphinscheduler.api.enums.Status.INTERNAL_SERVER_ERROR_ARGS;

import org.apache.dolphinscheduler.api.dto.BootstrapNamespaceResponse;
import org.apache.dolphinscheduler.api.exceptions.ApiException;
import org.apache.dolphinscheduler.api.service.QDataNamespaceService;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.plugin.task.api.utils.ParameterUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 获取token 基于 tenant and user
 */
@Tag(name = "QDATA_NAMESPACE_TAG")
@RestController
@RequestMapping("/qdata/namespaces")
@Slf4j
public class QDataBootstrapController extends BaseController {

    @Autowired
    private QDataNamespaceService qDataNamespaceService;

    @PostMapping(value = "/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiException(INTERNAL_SERVER_ERROR_ARGS)
    public Result<BootstrapNamespaceResponse> bootstrapNamespace(
                                                                 @Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                                 @RequestParam(value = "namespace", required = true) String namespace,
                                                                 @RequestParam(value = "displayName", required = false) String displayName,
                                                                 @RequestParam(value = "expireAt", required = false) String expireAt,
                                                                 @RequestParam(value = "resetIfExists", required = false) Boolean resetIfExists) {

        namespace = ParameterUtils.handleEscapes(namespace);
        displayName = ParameterUtils.handleEscapes(displayName);

        BootstrapNamespaceResponse resp =
                qDataNamespaceService.bootstrapNamespace(loginUser, namespace, displayName, expireAt, resetIfExists);

        return Result.success(resp);
    }

}
