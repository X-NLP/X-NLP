package com.xnlp.server.controller;

import com.xnlp.server.dto.QuotaResponse;
import com.xnlp.server.dto.QuotaUpdateRequest;
import com.xnlp.server.quota.TenantQuotaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/quota")
public class TenantQuotaController {

    private final TenantQuotaService quotas;

    public TenantQuotaController(TenantQuotaService quotas) {
        this.quotas = quotas;
    }

    @GetMapping
    public QuotaResponse get(@PathVariable String tenantId) {
        return quotas.get(tenantId);
    }

    @PutMapping
    public QuotaResponse update(
            @PathVariable String tenantId,
            @Valid @RequestBody QuotaUpdateRequest request) {
        return quotas.update(tenantId, request);
    }
}
