package com.xnlp.server.waste;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps approved work orders supplied with a short-lived, rotating authorization code. */
@Component
public class WasteAuthorizationScheduler {
    private final WasteApplicationService service;

    public WasteAuthorizationScheduler(WasteApplicationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 30_000)
    public void rotateExpiredCodes() {
        service.refreshExpiredAuthorizationCodes();
    }
}
