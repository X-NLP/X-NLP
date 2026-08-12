package com.xnlp.server.controller;

import com.xnlp.server.service.AIAssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
@Validated
public class AIAssistantController {

    private final AIAssistantService assistantService;

    public AIAssistantController(AIAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return assistantService.status();
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest request) {
        return assistantService.chat(request.message(), request.context(), request.modelName());
    }

    public record ChatRequest(@NotBlank String message, String context, String modelName) {
    }
}
