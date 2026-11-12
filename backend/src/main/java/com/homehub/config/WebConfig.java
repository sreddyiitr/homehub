package com.homehub.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the SPA. The browser loads the UI from one origin (Vite dev server
 * now, the S3 website endpoint later) and calls the API on another, so the
 * API must list the UI origin explicitly. Origins come from config only.
 */
@Configuration
@EnableConfigurationProperties(HomeHubProperties.class) // registers homehub.* (also in @WebMvcTest slices)
public class WebConfig implements WebMvcConfigurer {

    private final HomeHubProperties props;

    public WebConfig(HomeHubProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.cors().allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("ETag", "Location");
    }
}
