package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.dao.entity.User;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jwt.JWTClaimsSet;

@Slf4j
@Component
@RequiredArgsConstructor
public class SsoDeepLinkFilter extends OncePerRequestFilter {

    private final JwtVerifier verifier;
    private final UsersService usersService;
    private final SsoUserSessionBuilder sessionBuilder;

    @Value("${sso.redirectPrefix:/dolphinscheduler/ui/}")
    private String uiPrefix;

    private static final Pattern STATIC_EXT =
            Pattern.compile(".*\\.(js|css|map|png|jpg|jpeg|svg|gif|ico)$", Pattern.CASE_INSENSITIVE);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        final String uri = request.getRequestURI();
        final String ui = withTrailingSlash(uiPrefix);

        // —— 放行 DS 原生认证与管理端点，避免“串链/冲突”
        if (startsWithAny(uri,
                "/dolphinscheduler/login",
                "/dolphinscheduler/oauth2-provider",
                "/dolphinscheduler/redirect/login/oauth2",
                "/dolphinscheduler/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        // —— 仅处理 UI 前缀路径
        if (!uri.startsWith(ui)) {
            chain.doFilter(request, response);
            return;
        }

        // —— 放行登录页与静态资源（保证用户名/密码流程不受影响）
        if (isLoginPath(uri, ui) || isStaticPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // —— 只在 GET 且携带 _sso 时触发深链 SSO；否则放行
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String sso = request.getParameter("_sso");
        if (!StringUtils.hasText(sso)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // 1) 验 JWT
            JWTClaimsSet claims = verifier.verify(sso);
            String userName = claims.getStringClaim("userName");
            if (!StringUtils.hasText(userName)) {
                response.setStatus(HttpStatus.FOUND.value());
                response.setHeader("Location", ui + "login?error=sso_user");
                return;
            }

            // 2) 查用户
            User user = usersService.getUserByUserName(userName);
            if (user == null) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("user not found: " + userName);
                return;
            }

            // 3) 建立 DS 会话 + 下发 sessionId Cookie
            sessionBuilder.setup(user, request, response);

            // 4) 302 到“去掉 _sso”的同一路径（保留其他查询参数）
            String loc = stripSsoParam(request);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", loc);
            // 直接返回，避免继续进入链路被别的组件再次重定向
        } catch (Exception e) {
            log.warn("[SSO] verify/session failed: {}", e.toString(), e);
            // 验证失败：送 UI 登录页（避免落到 /login?error）
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", ui + "login?error=sso_invalid");
        }
    }

    private static boolean startsWithAny(String uri, String... prefixes) {
        if (uri == null || prefixes == null)
            return false;
        return Arrays.stream(prefixes).anyMatch(uri::startsWith);
    }

    private static boolean isStaticPath(String uri) {
        return STATIC_EXT.matcher(uri).matches() || uri.contains("/assets/");
    }

    private static boolean isLoginPath(String uri, String ui) {
        return uri.equals(ui + "login") || uri.startsWith(ui + "login/");
    }

    private static String withTrailingSlash(String s) {
        if (s == null || s.isEmpty())
            return "/dolphinscheduler/ui/";
        return s.endsWith("/") ? s : (s + "/");
    }

    private static String stripSsoParam(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String qs = req.getQueryString();
        if (qs == null || qs.isEmpty())
            return uri;
        StringBuilder sb = new StringBuilder();
        for (String p : qs.split("&")) {
            if (p.isEmpty() || p.startsWith("_sso="))
                continue;
            if (sb.length() > 0)
                sb.append('&');
            sb.append(p);
        }
        return sb.length() == 0 ? uri : (uri + "?" + sb);
    }

}
