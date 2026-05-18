package com.luqiang.seckill.config;

import com.luqiang.seckill.interceptor.JwtAuthInterceptor;
import com.luqiang.seckill.interceptor.RateLimiterInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/seckill.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // JWT 鉴权优先于限流
        registry.addInterceptor(new JwtAuthInterceptor())
                .addPathPatterns("/seckill/**")
                .excludePathPatterns("/seckill/stock/**"); // 库存查询无需登录

        registry.addInterceptor(new RateLimiterInterceptor())
                .addPathPatterns("/seckill/**");
    }
}
