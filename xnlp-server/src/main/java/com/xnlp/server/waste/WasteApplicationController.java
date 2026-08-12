package com.xnlp.server.waste;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/waste")
public class WasteApplicationController {
    private final WasteApplicationService service;

    public WasteApplicationController(WasteApplicationService service) { this.service = service; }

    @GetMapping("/vehicles") public List<Map<String, Object>> vehicles() { return service.vehicles(); }
    @GetMapping("/applications") public List<Map<String, Object>> applications(@RequestParam(required = false) String status) { return service.applications(status); }
    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return service.create(payload); }
    @GetMapping("/applications/{id}") public Map<String, Object> get(@PathVariable String id) { return service.get(id); }
    @GetMapping("/applications/{id}/audits") public List<Map<String, Object>> audits(@PathVariable String id) { return service.audits(id); }
    @PostMapping("/applications/{id}/review") public Map<String, Object> review(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return service.review(id, Boolean.parseBoolean(String.valueOf(payload.getOrDefault("approve", false))), String.valueOf(payload.getOrDefault("reviewer", "审核员")), String.valueOf(payload.getOrDefault("comment", "")));
    }
    @PostMapping("/applications/{id}/refresh-code") public Map<String, Object> refreshCode(@PathVariable String id) { return service.refreshCode(id); }
    @PostMapping("/gate/verify") public Map<String, Object> verifyGate(@RequestBody Map<String, Object> payload) { return service.verifyGate(String.valueOf(payload.getOrDefault("code", "")), String.valueOf(payload.getOrDefault("plateNo", ""))); }
    @PostMapping("/weighings") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> weighing(@RequestBody Map<String, Object> payload) { return service.addWeighing(payload); }
    @GetMapping("/ledger") public List<Map<String, Object>> ledger(@RequestParam(required = false) String plateNo, @RequestParam(required = false) String eventType) { return service.ledger(plateNo, eventType); }
    @GetMapping("/dashboard") public Map<String, Object> dashboard() { return service.dashboard(); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(RuntimeException e) { return Map.of("message", e.getMessage() == null ? "请求不合法" : e.getMessage()); }
}
