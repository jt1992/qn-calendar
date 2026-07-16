package com.qn.calendar.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationClockConfiguration {

    @Bean
    public Clock applicationClock(@Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
