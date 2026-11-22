package com.frontline.business;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.frontline.sandbox.*;

@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class BusinessConfiguration {
    @Bean
    public Clock realClock() { return Clock.system(BusinessCalendar.ZONE); }

    @Bean @Primary
    public Clock businessClock(SandboxProperties properties, SandboxStateRepository state, @Qualifier("realClock") Clock realClock) {
        return properties.enabled() ? new SandboxClock(state, realClock, BusinessCalendar.ZONE) : realClock;
    }

    @Bean
    public BusinessCalendar businessCalendar(Clock businessClock, HolidayRepository holidays,
            @org.springframework.beans.factory.annotation.Value("${frontline.calendar.always-open:false}") boolean alwaysOpen) {
        return new BusinessCalendar(businessClock, holidays::dates, alwaysOpen);
    }
}
