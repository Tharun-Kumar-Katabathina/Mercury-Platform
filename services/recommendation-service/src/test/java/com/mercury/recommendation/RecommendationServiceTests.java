package com.mercury.recommendation;

import com.mercury.recommendation.dto.RecommendedProduct;
import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.service.RecommendationService;
import com.mercury.recommendation.service.VectorIndex;
import com.mercury.recommendation.model.InteractionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/** The ranking logic, with the vector index replaced by a stub so every neighbour is under the test's control. */
@SpringBootTest
class RecommendationServiceTests {

    @Autowired private RecommendationService recommendations;
    @Autowired private FeatureStore store;
    @MockitoBean private VectorIndex index;

    private static String user() {
        return "user-" + UUID.randomUUID();
    }

    @Test
    void aCustomerGetsProductsRelatedToTheirStrongestInterestsAndNeverOnesTheyAlreadyBought() {
        String me = user();
        UUID bought = UUID.randomUUID(), viewed = UUID.randomUUID(), similarToBought = UUID.randomUUID(), similarToViewed = UUID.randomUUID();
        store.recordInteraction(me, bought, InteractionType.PURCHASE, Instant.now());
        store.recordInteraction(me, viewed, InteractionType.VIEW, Instant.now());
        when(index.similarTo(bought, 10)).thenReturn(List.of(new VectorIndex.Match(similarToBought, 0.9), new VectorIndex.Match(viewed, 0.8)));
        when(index.similarTo(viewed, 10)).thenReturn(List.of(new VectorIndex.Match(similarToViewed, 0.9), new VectorIndex.Match(bought, 0.7)));

        List<RecommendedProduct> result = recommendations.forUser(me, 10);

        assertThat(result).extracting(RecommendedProduct::productId).doesNotContain(bought);          // already purchased
        assertThat(result).extracting(RecommendedProduct::productId).first().isEqualTo(similarToBought);   // bought (weight 5) outranks viewed (weight 1)
        assertThat(result).extracting(RecommendedProduct::productId).contains(similarToViewed);
    }

    @Test
    void aNewCustomerWithNoHistoryGetsWhatIsPopular() {
        for (int product = 0; product < 6; product++) {            // enough products that a full page exists
            UUID p = UUID.randomUUID();
            for (int i = 0; i <= product; i++) {
                store.recordPurchase("pop-" + product + "-" + i, List.of(p), Instant.now());
            }
        }

        List<RecommendedProduct> result = recommendations.forUser(user(), 5);

        // the database is shared with the other tests, so compare with the popularity ranking itself
        assertThat(result).extracting(RecommendedProduct::productId)
                .containsExactlyElementsOf(store.popular(5).stream().map(FeatureStore.Scored::productId).toList());
        assertThat(result).hasSize(5);
    }

    @Test
    void theNumberOfResultsIsBoundedWhateverIsAsked() {
        UUID p = UUID.randomUUID();
        when(index.similarTo(any(), anyInt())).thenReturn(List.of());

        assertThat(recommendations.similar(p, 1_000_000)).isEmpty();
        assertThat(recommendations.popular(0).size()).isLessThanOrEqualTo(1);
    }

    @Test
    void boughtTogetherComesStraightFromTheCounts() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        store.recordPurchase(null, List.of(a, b), Instant.now());
        store.recordPurchase(null, List.of(a, b), Instant.now());
        store.recordPurchase(null, List.of(a, c), Instant.now());

        List<RecommendedProduct> result = recommendations.boughtTogether(a, 10);

        assertThat(result).extracting(RecommendedProduct::productId).containsExactly(b, c);
        assertThat(result.get(0).score()).isEqualTo(2.0);
    }
}
