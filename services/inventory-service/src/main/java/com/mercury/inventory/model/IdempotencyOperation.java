package com.mercury.inventory.model;

public enum IdempotencyOperation {
    RESERVE,
    RELEASE,
    /** tombstone: the key was fenced, so a reserve under it must never succeed */
    FENCED
}
