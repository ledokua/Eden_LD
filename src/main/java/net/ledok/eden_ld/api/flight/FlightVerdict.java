package net.ledok.eden_ld.api.flight;

public enum FlightVerdict {
    /** Force-allow, overriding lower-priority rules. */
    ALLOW,
    /** Block flight or boosting. */
    DENY,
    /** No opinion; defer to lower-priority rules. */
    PASS
}
