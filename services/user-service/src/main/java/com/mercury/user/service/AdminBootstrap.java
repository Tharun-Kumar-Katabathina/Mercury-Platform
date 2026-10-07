package com.mercury.user.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AuthService auth;

    public AdminBootstrap(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        auth.bootstrapAdmin();
    }
}
