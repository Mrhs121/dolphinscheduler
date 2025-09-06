package org.apache.dolphinscheduler.api.sso;

import org.apache.dolphinscheduler.api.service.SessionService;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.dao.entity.Session;
import org.apache.dolphinscheduler.dao.entity.User;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SsoUserSessionBuilder {

    private final SessionService sessionService;

    /** 建立 DS 会话 + 下发 Cookie（与 DS 自有登录保持一致） */
    public void setup(User user, HttpServletRequest req, HttpServletResponse resp) {
        if (user == null) {
            return;
        }

        // 1) 放入 HttpSession
        HttpSession httpSession = req.getSession(true);
        httpSession.setAttribute(Constants.SESSION_USER, user);

        // 2) 创建/续期后端 Session，并下发 sessionId Cookie
        Session dsSession = sessionService.createSessionIfAbsent(user);

        Cookie c = new Cookie(Constants.SESSION_ID, dsSession.getId());
        c.setHttpOnly(true);
        String ctx = req.getContextPath();
        c.setPath((ctx == null || ctx.isEmpty()) ? "/" : ctx);
        resp.addCookie(c);
    }
}
