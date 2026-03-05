package com.pipeline.intelligence_bot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class GitLabConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}