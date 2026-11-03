package com.cloudrescue.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    /** Window = number of monitor polls kept per service (about window x poll-interval seconds). */
    @Bean
    public AvailabilityTracker availabilityTracker(
            @Value("${cloudrescue.observability.availability-window:300}") int window) {
        return new AvailabilityTracker(window);
    }
}
