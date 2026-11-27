package com.nineouting.backend.admin.config;

import com.nineouting.backend.admin.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 관리자 화면(front)에서만 쿠키와 함께 부를 수 있게 하고, 모든 관리 API에 로그인 확인을 건다 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminProperties properties;
    private final AuthInterceptor authInterceptor;

    public WebConfig(AdminProperties properties, AuthInterceptor authInterceptor) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/admin/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("Content-Type", AuthInterceptor.REQUEST_HEADER)
                .allowCredentials(true)
                .maxAge(600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/admin/**");
    }
}
