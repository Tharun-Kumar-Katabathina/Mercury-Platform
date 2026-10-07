package com.mercury.recommendation.controller;

import com.mercury.recommendation.dto.RecommendedProduct;
import com.mercury.recommendation.service.RecommendationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /** Products bought with the same companions as this one (nearest neighbours in the vector index). */
    @GetMapping("/products/{productId}/similar")
    public List<RecommendedProduct> similar(@PathVariable UUID productId, @RequestParam(defaultValue = "10") int limit) {
        return recommendations.similar(productId, limit);
    }

    /** Products most often in the same order as this one. */
    @GetMapping("/products/{productId}/bought-together")
    public List<RecommendedProduct> boughtTogether(@PathVariable UUID productId, @RequestParam(defaultValue = "10") int limit) {
        return recommendations.boughtTogether(productId, limit);
    }

    @GetMapping("/popular")
    public List<RecommendedProduct> popular(@RequestParam(defaultValue = "10") int limit) {
        return recommendations.popular(limit);
    }

    /** Personalised for the calling customer (the token subject). */
    @GetMapping("/me")
    public List<RecommendedProduct> forMe(@RequestParam(defaultValue = "10") int limit, Authentication authentication) {
        return recommendations.forUser(InteractionController.UserIds.of(authentication), limit);
    }
}
