package com.smartexam.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashTest {

    private static final String TEST_PASSWORD_HASH =
            "$2a$10$m3Q7Hg7AWomEjgF1hgjuy.CWv760cerwLw0RkuuFG5JJtxbJCl8Da";

    @Test
    void sqlSeedUsesValidBcryptHash() {
        assertThat(new BCryptPasswordEncoder().matches("password", TEST_PASSWORD_HASH)).isTrue();
    }
}
