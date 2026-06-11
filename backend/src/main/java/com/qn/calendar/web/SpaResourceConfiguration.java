package com.qn.calendar.web;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class SpaResourceConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    private static final class SpaResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource indexPage = location.createRelative("index.html");

            if (resourcePath == null || resourcePath.isBlank()) {
                return readable(indexPage) ? indexPage : null;
            }

            Resource requestedResource = location.createRelative(resourcePath);
            if (readable(requestedResource)) {
                return requestedResource;
            }

            if (!isSpaRoute(resourcePath)) {
                return null;
            }

            return readable(indexPage) ? indexPage : null;
        }

        private boolean readable(Resource resource) {
            return resource.exists() && resource.isReadable();
        }

        private boolean isSpaRoute(String resourcePath) {
            return !resourcePath.equals("api")
                    && !resourcePath.startsWith("api/")
                    && !resourcePath.equals("error")
                    && !resourcePath.startsWith("error/")
                    && !resourcePath.contains(".");
        }
    }
}
