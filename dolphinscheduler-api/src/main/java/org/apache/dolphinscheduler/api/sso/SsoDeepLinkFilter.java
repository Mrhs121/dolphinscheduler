package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.dao.entity.User;

import java.io.IOException;
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

        // —— 显式放行 DS 原生认证端点，以免“冲突/串链”
        if (startsWithAny(uri,
                "/dolphinscheduler/login", // 含 /login 与 /login/sso
                "/dolphinscheduler/oauth2-provider" // DS 内置 OAuth2
        )) {
            chain.doFilter(request, response);
            return;
        }

        // —— 仅处理 UI 前缀路径
        if (!uri.startsWith(ui)) {
            chain.doFilter(request, response);
            return;
        }

        // —— 放行 UI 登录页与静态资源（保证用户名/密码登录流程不受影响）
        if (isLoginPath(uri, ui) || isStaticPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // —— 只在“页面直跳”场景触发：GET + 非 XHR
        if (!"GET".equalsIgnoreCase(request.getMethod()) || isAjax(request)) {
            chain.doFilter(request, response);
            return;
        }

        // —— 未携带 _sso：放行，交给原有 Security（未登录会被引导到 /ui/login 或既有逻辑）
        String sso = request.getParameter("_sso");
        if (!StringUtils.hasText(sso)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // —— 验签与会话建立
            JWTClaimsSet claims = verifier.verify(sso);
            String userName = String.valueOf(claims.getClaim("userName"));
            if (!StringUtils.hasText(userName)) {
                response.setStatus(HttpStatus.FOUND.value());
                response.setHeader("Location", ui + "login?error=sso_user");
                return;
            }

            User user = usersService.queryUser(userName);
            if (user == null) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.getWriter().write("user not found: " + userName);
                return;
            }

            sessionBuilder.setup(user, java.util.Collections.emptyList(), request);

            // —— 302 到“去掉 _sso”的同一路径（保留其他查询参数）
            String loc = stripSsoParam(request);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", loc);
            // 直接返回，避免继续进入 Security 再二次重定向
        } catch (Exception e) {
            log.warn("[SSO] verify/session failed: {}", e.toString(), e);
            // 验证失败：送 UI 登录页，而不是后端 /login?error
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader("Location", ui + "login?error=sso_invalid");
        }
    }

    private static boolean startsWithAny(String uri, String... prefixes) {
        for (String p : prefixes)
            if (uri.startsWith(p))
                return true;
        return false;
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

    private boolean isLoginPath(String uri, String ui) {
        return uri.equals(ui + "login") || uri.startsWith(ui + "login/");
    }

    private boolean isStaticPath(String uri) {
        return STATIC_EXT.matcher(uri).matches() || uri.contains("/assets/");
    }
}
