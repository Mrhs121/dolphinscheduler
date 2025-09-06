package org.apache.dolphinscheduler.api.sso;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SsoFilterConfig {

    private final SsoDeepLinkFilter ssoDeepLinkFilter;

    @Bean
    public FilterRegistrationBean<SsoDeepLinkFilter> ssoDeepLinkFilterRegistration() {
        FilterRegistrationBean<SsoDeepLinkFilter> reg = new FilterRegistrationBean<>(ssoDeepLinkFilter);
        reg.setOrder(Integer.MIN_VALUE);
        return reg;
    }
}
