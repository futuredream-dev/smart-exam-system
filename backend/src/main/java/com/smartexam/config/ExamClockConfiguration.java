package com.smartexam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class ExamClockConfiguration {
    @Bean
    public Clock examClock() { return Clock.systemUTC(); }
}
