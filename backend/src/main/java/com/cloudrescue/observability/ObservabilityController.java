package com.cloudrescue.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {
    private final ObservabilityService service;

    public ObservabilityController(ObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ObservabilityService.Summary summary() {
        return service.summary();
    }
}
