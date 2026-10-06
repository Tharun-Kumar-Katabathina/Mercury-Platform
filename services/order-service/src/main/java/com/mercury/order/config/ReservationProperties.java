package com.mercury.order.config;

import com.mercury.order.model.ReservationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** order.reservation.*: which reservation flow new orders use, and how long ASYNC orders wait. */
@ConfigurationProperties(prefix = "order.reservation")
public record ReservationProperties(
        /** SYNC (default) or ASYNC; applies to NEW orders only, each order keeps the mode it was created with */
        @DefaultValue("SYNC") ReservationMode mode,
        /** how long an ASYNC order waits for Inventory's reply before recovery asks Inventory directly */
        @DefaultValue("60s") Duration asyncDeadline
) {
}
