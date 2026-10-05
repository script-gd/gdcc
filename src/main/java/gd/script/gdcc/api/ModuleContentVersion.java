package gd.script.gdcc.api;

/// The (moduleGeneration, contentVersion) pair identifying one frozen module content state.
///
/// `moduleGeneration` is allocated once at `createModule` from a global monotonic counter and never
/// reused, so a same-id module recreated after deletion always has a higher generation.
/// `contentVersion` counts content-changing writes within one generation. Staleness checks must
/// compare both components — comparing only the version misfires after delete/recreate, where the
/// new module's version restarts from a low value.
public record ModuleContentVersion(long moduleGeneration, long contentVersion) {
}
