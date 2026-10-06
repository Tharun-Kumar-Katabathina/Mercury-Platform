package com.mercury.inventory.dto;

/** Outcome of a release call; {@code replayed} is true when a stored result was returned. */
public record ReleaseResult(ReleaseResponse response, boolean replayed) {
}
