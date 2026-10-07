-- Accounts. Passwords are stored only as bcrypt hashes; nothing here can be turned back into a password.
CREATE TABLE users (
    id              UUID                     NOT NULL,
    email           VARCHAR(254)             NOT NULL,
    password_hash   VARCHAR(100)             NOT NULL,
    roles           VARCHAR(100)             NOT NULL,
    enabled         BOOLEAN                  NOT NULL,
    failed_attempts INTEGER                  NOT NULL,
    locked_until    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_attempts >= 0)
);
