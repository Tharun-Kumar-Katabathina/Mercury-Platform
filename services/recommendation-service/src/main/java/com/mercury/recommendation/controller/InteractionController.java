package com.mercury.recommendation.controller;

import com.mercury.recommendation.dto.InteractionRequest;
import com.mercury.recommendation.model.InteractionType;
import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.exception.ValidationFailedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/interactions")
public class InteractionController {

    private final FeatureStore store;

    public InteractionController(FeatureStore store) {
        this.store = store;
    }

    /** The calling customer looked at, or added to a cart, a product. Purchases are learned from order events only. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void record(@Valid @RequestBody InteractionRequest request, Authentication authentication) {
        if (request.type() == InteractionType.PURCHASE) {
            throw new ValidationFailedException("purchases are learned from confirmed orders and cannot be reported");
        }
        store.recordInteraction(UserIds.of(authentication), request.productId(), request.type(), Instant.now());
    }

    /** The customer id is always the token subject: nobody can record behaviour on someone else's behalf. */
    static final class UserIds {
        private UserIds() { }

        static String of(Authentication authentication) {
            return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : "anonymous";
        }
    }
}
