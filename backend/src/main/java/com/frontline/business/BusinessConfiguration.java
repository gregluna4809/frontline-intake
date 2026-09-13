package com.frontline.business;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BusinessConfiguration {
    @Bean
    public Clock businessClock() { return Clock.system(BusinessCalendar.ZONE); }

    @Bean
    public BusinessCalendar businessCalendar(Clock businessClock) { return new BusinessCalendar(businessClock); }
}
