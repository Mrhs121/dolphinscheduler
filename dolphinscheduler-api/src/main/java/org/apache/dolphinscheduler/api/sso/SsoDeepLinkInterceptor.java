package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.regex.Pattern;

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

    private static final Pattern STATIC_EXT =
            Pattern.compile(".*\\.(js|css|map|png|jpg|jpeg|svg|gif|ico)$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        final String ui = withTrailingSlash(uiPrefix);
        final String uri = request.getRequestURI();
        // 仅处理 UI 前缀
        if (!uri.startsWith(ui)) {
            return true;
        }
        // 放行：登录页、静态资源（正常用户名/密码登录不受影响）
        if (isLoginPath(uri, ui) || isStaticPath(uri)) {
            return true;
        }
        // 只在“页面直跳”场景触发：GET + Accept:text/html + 非XHR
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !acceptsHtml(request) || isAjax(request)) {
            return true;
        }
        // 没带 _sso：放行，由原有鉴权/前端路由决定（未登录会跳到 UI 登录页）
        String sso = request.getParameter("_sso");
        if (!StringUtils.hasText(sso)) {
            return true;
        }
        try {
            JWTClaimsSet claims = verifier.verify(sso);
            String userName = asString(claims.getClaim("userName"));
            if (!StringUtils.hasText(userName)) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), "bad token: userName missing");
                return false;
            }

            User user = usersService.queryUser(userName);
            if (user == null) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "user not found: " + userName);
                return false;
            }
            // 建立会话
            sessionBuilder.setup(user, java.util.Collections.emptyList(), request);

            // 302 到去掉 _sso 的地址（保留其它 query）
            String loc = stripSsoParam(request);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", loc);
            return false;

        } catch (Exception e) {
            log.warn("[SSO] verify/session failed: {}", e.toString(), e);
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

    private static String withTrailingSlash(String s) {
        if (s == null || s.isEmpty())
            return "/dolphinscheduler/ui/";
        return s.endsWith("/") ? s : (s + "/");
    }

    private static boolean isAjax(HttpServletRequest req) {
        String xrw = req.getHeader("X-Requested-With");
        return xrw != null && "XMLHttpRequest".equalsIgnoreCase(xrw);
    }

    private static boolean acceptsHtml(HttpServletRequest req) {
        String acc = req.getHeader("Accept");
        return acc != null && acc.toLowerCase().contains("text/html");
    }

    private boolean isLoginPath(String uri, String ui) {
        return uri.equals(ui + "login") || uri.startsWith(ui + "login/");
    }

    private boolean isStaticPath(String uri) {
        return STATIC_EXT.matcher(uri).matches() || uri.contains("/assets/");
    }
}
