package org.apache.dolphinscheduler.api.sso;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class SsoWebConfig implements WebMvcConfigurer {

    private final SsoDeepLinkInterceptor interceptor;

    @Value("${sso.redirectPrefix:/dolphinscheduler/ui/}")
    private String uiPrefix;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        String ui = withTrailingSlash(uiPrefix);
        registry.addInterceptor(interceptor)
                .addPathPatterns(ui + "**")
                // —— 排除登录与静态资源（保留首页与 index.html，让 /ui/?_sso= 能触发）
                .excludePathPatterns(
                        ui + "login", ui + "login/**",
                        ui + "assets/**",
                        ui + "**/*.js",
                        ui + "**/*.css",
                        ui + "**/*.map",
                        ui + "**/*.png",
                        ui + "**/*.jpg",
                        ui + "**/*.jpeg",
                        ui + "**/*.svg",
                        ui + "**/*.gif",
                        ui + "favicon.ico")
                .order(0);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
    }

    private static String withTrailingSlash(String s) {
        if (s == null || s.isEmpty())
            return "/dolphinscheduler/ui/";
        return s.endsWith("/") ? s : (s + "/");
    }
}
