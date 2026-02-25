package dev.superice.gdcc.enums;

/// Provenance for lifecycle instructions (`destruct`, `try_own_object`, `try_release_object`).
public enum LifecycleProvenance {
    AUTO_GENERATED,
    INTERNAL,
    USER_EXPLICIT,
    UNKNOWN
}
