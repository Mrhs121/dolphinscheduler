package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class SsoUserSessionBuilder {

    /** 避免引入额外依赖，直接使用标准 key 字符串 */
    private static final String SPRING_SECURITY_CONTEXT_KEY =
            "SPRING_SECURITY_CONTEXT";

    public void setup(User user, List<GrantedAuthority> auths, HttpServletRequest req) {
        if (user == null)
            return;

        if (CollectionUtils.isEmpty(auths)) {
            auths = Collections.emptyList();
        }
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getUserName(), "N/A", auths);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));

        // 放入 SecurityContext
        org.springframework.security.core.context.SecurityContext context =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);

        // 放入 HttpSession
        HttpSession session = req.getSession(true);
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);

        // 兼容 DS 代码中读取的会话用户（很多地方用 Constants.SESSION_USER）
        session.setAttribute(Constants.SESSION_USER, user);
    }
}
