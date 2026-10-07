package com.mercury.recommendation.dto;

import java.util.UUID;

/** One recommended product and how strongly it is recommended (higher is stronger; comparable within one list). */
public record RecommendedProduct(UUID productId, double score) {
}
