package com.mercury.order.service;

import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.SagaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finishes sagas that did not finish on their own: a request that timed out, a process that
 * crashed, a compensation that failed. One pass is idempotent and safe to run from several
 * instances at once: sagas are claimed with row locks and a lease (see OrderTransactions.claimDue),
 * and every remote call it makes is idempotent by key.
 *
 * Retries are bounded: each failed attempt waits longer (exponential backoff, configured in
 * RecoveryProperties) and after the configured maximum the saga becomes RECOVERY_FAILED.
 */
@Service
public class SagaRecovery {

    private static final Logger log = LoggerFactory.getLogger(SagaRecovery.class);

    private final OrderTransactions transactions;
    private final OrderSagaService sagaService;
    private final SagaMetrics metrics;

    public SagaRecovery(
            OrderTransactions transactions, OrderSagaService sagaService, SagaMetrics metrics) {
        this.transactions = transactions;
        this.sagaService = sagaService;
        this.metrics = metrics;
    }

    /** @return how many sagas this pass drove to a final state */
    public int recoverDue() {

        List<UUID> claimed = transactions.claimDue();
        int finished = 0;

        for (UUID orderId : claimed) {
            try {
                if (recover(orderId)) {
                    finished++;
                }
            } catch (RuntimeException e) {
                // one broken saga must not stop the others
                log.error("recovery orderId={} crashed", orderId, e);
                metrics.recoveryAttemptFailed();
            }
        }
        return finished;
    }

    private boolean recover(UUID orderId) {

        Optional<OrderTransactions.SagaStateView> saga = transactions.sagaOf(orderId);
        if (saga.isEmpty() || !saga.get().state().isActive()) {
            return false;   // finished by someone else in the meantime
        }

        Optional<OrderStatus> status = transactions.statusOf(orderId);
        if (status.isPresent() && status.get() != OrderStatus.PENDING) {
            log.warn("recovery orderId={} order is already {} but its saga is {}; nothing to undo",
                    orderId, status.get(), saga.get().state());
            return false;
        }

        log.info("recovery orderId={} state={} attempt={}",
                orderId, saga.get().state(), saga.get().attemptCount() + 1);

        boolean finished = sagaService.compensate(orderId);
        if (finished) {
            metrics.recoverySucceeded();
            log.info("recovery orderId={} result=CANCELLED", orderId);
            return true;
        }

        OrderTransactions.SagaStateView after = transactions.scheduleRetry(
                orderId, "recovery attempt " + (saga.get().attemptCount() + 1) + " did not finish");
        metrics.recoveryAttemptFailed();
        if (after.state() == SagaState.RECOVERY_FAILED) {
            log.error("recovery orderId={} gave up after {} attempts; needs a person",
                    orderId, after.attemptCount());
        } else {
            log.warn("recovery orderId={} attempt={} failed; next attempt at {}",
                    orderId, after.attemptCount(), after.nextAttemptAt());
        }
        return false;
    }
}
