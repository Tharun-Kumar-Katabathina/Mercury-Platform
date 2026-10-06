package com.mercury.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs a recovery pass on a fixed delay. Switch off with order.recovery.enabled=false. */
@Component
@ConditionalOnProperty(name = "order.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class SagaRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryWorker.class);

    private final SagaRecovery recovery;

    public SagaRecoveryWorker(SagaRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${order.recovery.interval:10s}",
            initialDelayString = "${order.recovery.interval:10s}")
    public void run() {
        try {
            int finished = recovery.recoverDue();
            if (finished > 0) {
                log.info("recovery pass finished {} saga(s)", finished);
            }
        } catch (RuntimeException e) {
            log.error("recovery pass failed", e);
        }
    }
}
