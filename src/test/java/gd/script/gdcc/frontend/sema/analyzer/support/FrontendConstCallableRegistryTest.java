package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Builder/freeze and metadata-validation contracts for `FrontendConstCallableRegistry`, plus the
/// selection helper it validates against: every registered key must be exactly what ordinary
/// overload resolution picks for the declared argument types.
class FrontendConstCallableRegistryTest {
    private static final @NotNull ClassRegistry REGISTRY = loadRegistry();
    private static final @NotNull ConstMemberEvaluator STUB_HANDLER =
            (receiver, arguments) -> new ConstCallResult.Resolved(new GdConstantValue.BoolValue(true));

    private static final @NotNull ConstCallableKey VECTOR2_LENGTH_KEY = new ConstCallableKey(
            GdFloatVectorType.VECTOR2, "length",
            ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of());
    private static final @NotNull ConstCallableKey STRING_CONTAINS_KEY = new ConstCallableKey(
            GdStringType.STRING, "contains",
            ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING));

    // ------------------------------------------------------------------
    // Registration and freeze
    // ------------------------------------------------------------------

    @Test
    void emptyRegistryRejectsEveryKey() {
        assertNull(FrontendConstCallableRegistry.empty().find(VECTOR2_LENGTH_KEY));
    }

    @Test
    void registersAndFreezesEntriesAgainstRealMetadata() {
        var registry = FrontendConstCallableRegistry.builder(REGISTRY)
                .register(VECTOR2_LENGTH_KEY, GdFloatType.FLOAT, STUB_HANDLER)
                .register(STRING_CONTAINS_KEY, GdBoolType.BOOL, STUB_HANDLER)
                .build();
        var lengthEntry = java.util.Objects.requireNonNull(registry.find(VECTOR2_LENGTH_KEY));
        var containsEntry = java.util.Objects.requireNonNull(registry.find(STRING_CONTAINS_KEY));
        assertAll(
                () -> assertEquals(GdFloatType.FLOAT, lengthEntry.returnType()),
                () -> assertSame(STUB_HANDLER, lengthEntry.evaluator()),
                () -> assertEquals(GdBoolType.BOOL, containsEntry.returnType()),
                // Unregistered-but-selectable signatures stay unregistered.
                () -> assertNull(registry.find(new ConstCallableKey(
                        GdFloatVectorType.VECTOR2, "normalized",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of())))
        );
    }

    @Test
    void frozenRegistryKeepsEntriesImmutableAcrossRebuild() {
        var builder = FrontendConstCallableRegistry.builder(REGISTRY)
                .register(VECTOR2_LENGTH_KEY, GdFloatType.FLOAT, STUB_HANDLER);
        var first = builder.build();
        // build() must freeze a snapshot; the builder itself rejects later mutation.
        assertAll(
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> builder.register(STRING_CONTAINS_KEY, GdBoolType.BOOL, STUB_HANDLER)
                ),
                () -> assertNull(first.find(STRING_CONTAINS_KEY)),
                () -> assertNotNull(first.find(VECTOR2_LENGTH_KEY))
        );
    }

    @Test
    void rejectsDuplicateSignatures() {
        var builder = FrontendConstCallableRegistry.builder(REGISTRY)
                .register(VECTOR2_LENGTH_KEY, GdFloatType.FLOAT, STUB_HANDLER);
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> builder.register(VECTOR2_LENGTH_KEY, GdFloatType.FLOAT, STUB_HANDLER)
        );
        assertTrue(thrown.getMessage().contains("duplicate"));
    }

    // ------------------------------------------------------------------
    // Metadata validation — malformed signatures are programmer errors
    // ------------------------------------------------------------------

    @Test
    void rejectsSignaturesThatDoNotExist() {
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.register(
                                new ConstCallableKey(
                                        GdFloatVectorType.VECTOR2, "nonexistent",
                                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of()),
                                GdFloatType.FLOAT,
                                STUB_HANDLER
                        )
                ),
                // Declared parameter types do not match the real signature.
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.register(
                                new ConstCallableKey(
                                        GdFloatVectorType.VECTOR2, "length",
                                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                        List.of(GdIntType.INT)),
                                GdFloatType.FLOAT,
                                STUB_HANDLER
                        )
                )
        );
    }

    @Test
    void rejectsMismatchedReturnTypes() {
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> builder.register(VECTOR2_LENGTH_KEY, GdIntType.INT, STUB_HANDLER)
        );
        assertTrue(thrown.getMessage().contains("return type"));
    }

    @Test
    void rejectsStaticOnlyMethods() {
        // `Vector2.from_angle` and `String.num` exist only as static metadata — registering them
        // under the instance-method kind fails selection, exactly like a missing method.
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.register(
                                new ConstCallableKey(
                                        GdFloatVectorType.VECTOR2, "from_angle",
                                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                        List.of(GdFloatType.FLOAT)),
                                GdFloatVectorType.VECTOR2,
                                STUB_HANDLER
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.register(
                                new ConstCallableKey(
                                        GdStringType.STRING, "num",
                                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                        List.of(GdFloatType.FLOAT)),
                                GdStringType.STRING,
                                STUB_HANDLER
                        )
                )
        );
    }

    @Test
    void rejectsArgumentsOutsideTheConstantTypeSurface() {
        // `String.format(Variant)` and `String.join(PackedStringArray)` take argument types the
        // frozen value model cannot represent — selection fails them before any registration.
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.register(
                                new ConstCallableKey(
                                        GdStringType.STRING, "format",
                                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                        List.of(GdVariantType.VARIANT)),
                                GdStringType.STRING,
                                STUB_HANDLER
                        )
                )
        );
    }

    @Test
    void rejectsOmittedDefaultParameterSignatures() {
        // `substr(from: int, len: int = -1)` selects with two declared parameters; registering a
        // partial key cannot reproduce the selected signature.
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.register(
                        new ConstCallableKey(
                                GdStringType.STRING, "substr",
                                ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdIntType.INT)),
                        GdStringType.STRING,
                        STUB_HANDLER
                )
        );
        // The full declared signature registers cleanly.
        assertNotNull(builder
                .register(
                        new ConstCallableKey(
                                GdStringType.STRING, "substr",
                                ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                List.of(GdIntType.INT, GdIntType.INT)),
                        GdStringType.STRING,
                        STUB_HANDLER
                )
                .build()
                .find(new ConstCallableKey(
                        GdStringType.STRING, "substr",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                        List.of(GdIntType.INT, GdIntType.INT))));
    }

    @Test
    void rejectsOwnersOutsideTheBuiltinConstantSurface() {
        // Engine/Object receivers have no compile-time dispatch surface — instance keys against
        // them fail selection just like a malformed signature.
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.register(
                        new ConstCallableKey(
                                new GdObjectType("Node"), "get_name",
                                ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of()),
                        GdStringType.STRING,
                        STUB_HANDLER
                )
        );
    }

    @Test
    void dispatchesRegisteredHandlers() {
        var registry = FrontendConstCallableRegistry.builder(REGISTRY)
                .register(
                        STRING_CONTAINS_KEY,
                        GdBoolType.BOOL,
                        (receiver, args) -> new ConstCallResult.Resolved(new GdConstantValue.BoolValue(
                                ((GdConstantValue.StringValue) receiver).value()
                                        .contains(((GdConstantValue.StringValue) args.get(0)).value())))
                )
                .build();
        var result = registry.find(STRING_CONTAINS_KEY).evaluator().evaluate(
                new GdConstantValue.StringValue("abc"),
                List.of(new GdConstantValue.StringValue("b"))
        );
        var resolved = assertInstanceOf(ConstCallResult.Resolved.class, result);
        assertEquals(new GdConstantValue.BoolValue(true), resolved.value());
    }

    // ------------------------------------------------------------------
    // Selection helper — shared with `LookupContext.selectCall`
    // ------------------------------------------------------------------

    @Test
    void selectionResolvesDeclaredSignaturesByArgumentTypes() {
        var lengthSelection = FrontendConstCallSelectionSupport.select(
                REGISTRY, GdFloatVectorType.VECTOR2, "length", List.of());
        var containsSelection = FrontendConstCallSelectionSupport.select(
                REGISTRY, GdStringType.STRING, "contains", List.of(GdStringType.STRING));
        assertAll(
                () -> {
                    var selected = assertInstanceOf(LookupContext.SelectionResult.Selected.class, lengthSelection);
                    assertEquals(VECTOR2_LENGTH_KEY, selected.key());
                    assertEquals(GdFloatType.FLOAT, selected.returnType());
                },
                () -> {
                    var selected = assertInstanceOf(LookupContext.SelectionResult.Selected.class, containsSelection);
                    assertEquals(STRING_CONTAINS_KEY, selected.key());
                    assertEquals(GdBoolType.BOOL, selected.returnType());
                }
        );
    }

    @Test
    void selectionPicksDeclaredParametersThroughBoundaryConversions() {
        // `move_toward(Vector2i, int)` resolves `move_toward(to: Vector2, delta: float)` — the
        // key carries the *declared* parameter types, not the provided ones, which is exactly
        // what registry lookup keys need.
        var selection = FrontendConstCallSelectionSupport.select(
                REGISTRY,
                GdFloatVectorType.VECTOR2,
                "move_toward",
                List.of(GdIntVectorType.VECTOR2I, GdIntType.INT)
        );
        var selected = assertInstanceOf(LookupContext.SelectionResult.Selected.class, selection);
        assertEquals(
                new ConstCallableKey(
                        GdFloatVectorType.VECTOR2, "move_toward",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                        List.of(GdFloatVectorType.VECTOR2, GdFloatType.FLOAT)),
                selected.key()
        );
        assertEquals(GdFloatVectorType.VECTOR2, selected.returnType());
    }

    @Test
    void selectionRejectsOutsideSurfaceCalls() {
        assertAll(
                () -> {
                    var rejected = assertInstanceOf(
                            LookupContext.SelectionResult.Rejected.class,
                            FrontendConstCallSelectionSupport.select(
                                    REGISTRY, GdFloatVectorType.VECTOR2, "nonexistent", List.of()));
                    assertTrue(rejected.reason().contains("not found"));
                },
                // Variant receivers have no static dispatch surface.
                () -> assertInstanceOf(
                        LookupContext.SelectionResult.Rejected.class,
                        FrontendConstCallSelectionSupport.select(
                                REGISTRY, GdVariantType.VARIANT, "x", List.of())),
                // Argument types outside the const surface fail before overload resolution.
                () -> assertInstanceOf(
                        LookupContext.SelectionResult.Rejected.class,
                        FrontendConstCallSelectionSupport.select(
                                REGISTRY, GdStringType.STRING, "format", List.of(GdVariantType.VARIANT))),
                // Static methods cannot bind through instance dispatch.
                () -> assertInstanceOf(
                        LookupContext.SelectionResult.Rejected.class,
                        FrontendConstCallSelectionSupport.select(
                                REGISTRY, GdStringType.STRING, "num", List.of(GdFloatType.FLOAT))),
                // Omitted defaults are not filled for constant calls.
                () -> {
                    var rejected = assertInstanceOf(
                            LookupContext.SelectionResult.Rejected.class,
                            FrontendConstCallSelectionSupport.select(
                                    REGISTRY, GdStringType.STRING, "substr", List.of(GdIntType.INT)));
                    assertTrue(rejected.reason().contains("omitted-default"));
                }
        );
    }

    private static @NotNull ClassRegistry loadRegistry() {
        try {
            return new ClassRegistry(ExtensionApiLoader.loadDefault());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
