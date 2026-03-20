package dev.superice.gdcc.frontend.sema;

/// Semantic receiver family seen by a chain/member/call step.
///
/// This enum answers "what kind of receiver reached the current step?" without overloading
/// `FrontendBindingKind` with route-specific state.
public enum FrontendReceiverKind {
    /// The analyzer does not have a stable receiver family yet.
    ///
    /// Typical cases:
    /// - the current step has not finished local reduction
    /// - upstream analysis stopped before a concrete receiver family could be published
    /// - the published status is about a failure/unsupported boundary rather than a real receiver
    UNKNOWN,
    /// The step is being resolved against an instance-style value receiver.
    ///
    /// This covers ordinary object/builtin/value receivers such as:
    /// - `player.hp`
    /// - `player.move()`
    /// - `vector.x`
    ///
    /// It does not imply that the step resolved successfully; a step may still be blocked,
    /// deferred, dynamic, or failed while remaining on an instance-style route.
    INSTANCE,
    /// The step is being resolved against a type-meta/static receiver.
    ///
    /// This is used when the chain head or current step is already known to be class-like, for
    /// example:
    /// - `ClassName.static_method()`
    /// - `ClassName.new()`
    /// - `EnumType.VALUE`
    ///
    /// The purpose is to separate static/type-meta routes from ordinary instance lookup.
    TYPE_META,
    /// The receiver is already on a runtime-dynamic route.
    ///
    /// Once a step has crossed into runtime-dynamic handling, downstream suffix analysis must stop
    /// pretending it still has exact static receiver metadata. This enum member preserves that
    /// provenance explicitly for later recovery and typing stages.
    DYNAMIC
}
