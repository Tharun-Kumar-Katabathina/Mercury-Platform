package com.mercury.user.dto;

import com.mercury.user.model.Role;
import com.mercury.user.model.User;

import java.util.Set;
import java.util.UUID;

/** Never carries the password hash, the lock state or anything else an attacker could use. */
public record UserResponse(UUID id, String email, Set<Role> roles) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRoles());
    }
}
