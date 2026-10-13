package com.mercury.user;

import com.mercury.user.model.Role;
import com.mercury.user.model.User;
import com.mercury.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timestamp columns keep microseconds; the clock is finer on Linux (nanoseconds). A row stamped from the raw clock
 * differs from the entity that was written, because the database rounds what it cannot keep. Each row is written many
 * times so that the difference would show on any host whose clock is finer than the column.
 */
@SpringBootTest
class TimestampPrecisionTests {

    private static final int ROUNDS = 25;

    @Autowired private UserRepository users;

    private User newUser() {
        return users.saveAndFlush(new User("precision-" + UUID.randomUUID() + "@example.com", "hash", Set.of(Role.USER)));
    }

    @Test
    void aUserIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            User written = newUser();
            User stored = users.findById(written.getId()).orElseThrow();
            assertThat(stored.getCreatedAt()).isEqualTo(written.getCreatedAt());
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }

    @Test
    void aUserUpdateIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            User user = newUser();
            user.loginFailed(java.time.Instant.now(), 5, java.time.Duration.ofMinutes(1));
            User written = users.saveAndFlush(user);
            User stored = users.findById(written.getId()).orElseThrow();
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }
}
