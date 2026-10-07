package com.mercury.recommendation.dto;

import com.mercury.recommendation.model.InteractionType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** A customer looked at or carted a product. (Purchases are learned from order events, never reported by clients.) */
public record InteractionRequest(@NotNull UUID productId, @NotNull InteractionType type) {
}
