package com.ainexus.controller;

import com.ainexus.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探活。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("UP");
    }
}
