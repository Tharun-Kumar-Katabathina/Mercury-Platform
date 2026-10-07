package com.mercury.user.service;

import com.mercury.user.config.SecurityProperties;
import com.mercury.user.dto.LoginRequest;
import com.mercury.user.dto.RegisterRequest;
import com.mercury.user.dto.ServiceTokenRequest;
import com.mercury.user.dto.TokenResponse;
import com.mercury.user.dto.UserResponse;
import com.mercury.user.exception.EmailAlreadyRegisteredException;
import com.mercury.user.exception.InvalidCredentialsException;
import com.mercury.user.exception.TooManyAttemptsException;
import com.mercury.user.model.Role;
import com.mercury.user.model.User;
import com.mercury.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LoginRateLimiter limiter;
    private final SecurityProperties properties;
    private final Clock clock;
    /** hash compared against when the account does not exist, so "no such user" costs the same time as "wrong password" */
    private final String dummyHash;
    private final Map<String, String> serviceClientHashes = new HashMap<>();

    public AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, LoginRateLimiter limiter,
                       SecurityProperties properties, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
        for (String entry : properties.serviceClients()) {
            int colon = entry.indexOf(':');
            if (colon > 0 && colon < entry.length() - 1) {
                serviceClientHashes.put(entry.substring(0, colon), encoder.encode(entry.substring(colon + 1)));
            }
        }
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalise(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        try {
            return UserResponse.from(users.saveAndFlush(new User(email, encoder.encode(request.password()), Set.of(Role.USER))));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException();     // a concurrent registration won the race
        }
    }

    /** Creates the first administrator from configuration; does nothing when it exists or is not configured. */
    @Transactional
    public void bootstrapAdmin() {
        String email = properties.adminEmail();
        String password = properties.adminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (!users.existsByEmail(normalise(email))) {
            users.save(new User(normalise(email), encoder.encode(password), Set.of(Role.ADMIN, Role.USER)));
            log.info("bootstrap administrator created");
        }
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public TokenResponse login(LoginRequest request, String clientKey) {
        if (!limiter.tryAcquire(clientKey)) {
            throw new TooManyAttemptsException();
        }
        Instant now = Instant.now(clock);
        User user = users.findByEmail(normalise(request.email())).orElse(null);
        if (user == null) {
            encoder.matches(request.password(), dummyHash);          // same cost as a real check
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled() || user.isLocked(now)) {
            encoder.matches(request.password(), dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            user.loginFailed(now, properties.maxFailedLogins(), properties.lockDuration());
            users.save(user);                                        // the failure is recorded even though we throw
            throw new InvalidCredentialsException();
        }
        user.loginSucceeded();
        users.save(user);
        return tokens.forUser(user.getId(), user.getEmail(), user.getRoles());
    }

    public TokenResponse serviceToken(ServiceTokenRequest request, String clientKey) {
        if (!limiter.tryAcquire("service:" + clientKey)) {
            throw new TooManyAttemptsException();
        }
        String hash = serviceClientHashes.get(request.clientId());
        boolean ok = encoder.matches(request.clientSecret(), hash == null ? dummyHash : hash) && hash != null;
        if (!ok) {
            throw new InvalidCredentialsException();
        }
        return tokens.forService(request.clientId());
    }

    @Transactional(readOnly = true)
    public UserResponse profile(String userId) {
        return users.findById(java.util.UUID.fromString(userId)).map(UserResponse::from)
                .orElseThrow(InvalidCredentialsException::new);
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** constant-time equality for anything that compares secrets */
    static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
