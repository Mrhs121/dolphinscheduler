package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.dao.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.nimbusds.jwt.JWTClaimsSet;

@Slf4j
@Component
@RequiredArgsConstructor
public class SsoDeepLinkInterceptor implements HandlerInterceptor {

    private final JwtVerifier verifier;
    private final UsersService usersService;
    private final SsoUserSessionBuilder sessionBuilder;

    @Value("${sso.redirectPrefix:/dolphinscheduler/ui/}")
    private String uiPrefix;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        String uri = request.getRequestURI();
        String sso = request.getParameter("_sso");

        // 非 UI 或无 _sso 参数：放行
        if (sso == null || !uri.startsWith(uiPrefix)) {
            return true;
        }

        try {
            JWTClaimsSet claims = verifier.verify(sso);
            String userName = asString(claims.getClaim("userName"));
            if (!StringUtils.hasText(userName)) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), "bad token: userName missing");
                return false;
            }

            // 仅“查”用户是否存在
            User user = usersService.queryUser(userName);
            if (user == null) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "user not found: " + userName);
                return false;
            }

            // 建立会话
            sessionBuilder.setup(user, java.util.Collections.emptyList(), request);

            // 302 到“去掉 _sso”的同一路径（保留其余 query）
            String loc = stripSsoParam(request);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", loc);
            return false;

        } catch (Exception e) {
            log.warn("[SSO] verify or session failed: {}", e.toString(), e);
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "invalid sso token");
            return false;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String stripSsoParam(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String qs = req.getQueryString();
        if (qs == null || qs.isEmpty())
            return uri;

        // 去掉 _sso=xxx（考虑 & 的位置
        String[] parts = qs.split("&");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty())
                continue;
            if (p.startsWith("_sso="))
                continue;
            if (sb.length() > 0)
                sb.append('&');
            sb.append(p);
        }
        return sb.length() == 0 ? uri : (uri + "?" + sb.toString());
    }
}
