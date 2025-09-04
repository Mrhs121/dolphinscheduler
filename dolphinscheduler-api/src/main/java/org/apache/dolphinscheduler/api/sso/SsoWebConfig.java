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
        // 只匹配 UI 前缀，避免影响 API
        registry.addInterceptor(interceptor)
                .addPathPatterns(uiPrefix + "**")
                .order(0); // 提前处理
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
    }
}
