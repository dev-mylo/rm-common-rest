package com.rm.process.rest.configuration;

import com.rm.process.rest.util.CorsUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import static org.springframework.util.ObjectUtils.nullSafeEquals;

@Configuration
public class CorsConfiguration implements InitializingBean {
    @Value("${spring.profiles.active:local}")
    private String profile;

    @Value("${mylo.cors.develop:false}")
    private boolean corsDevelop;

    @Value("${process-rest.allow-domain}")
    private String[] allowDomainList;

    @Override
    public void afterPropertiesSet() {
        if (!nullSafeEquals(profile, "real")) corsDevelop = true;

        CorsUtils.setDevelop(corsDevelop);
        CorsUtils.setAllowDomainList(allowDomainList);

    }
}
