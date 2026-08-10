package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibility;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Frozen EXPR_TYPE fact describing how one array/dictionary literal constructs its container.
///
/// Published into `FrontendAnalysisData.containerLiteralPlans()` and shared by later type-check /
/// CFG / lowering as the single element-boundary source of truth. Constraints:
/// - `resultType` is only `GdArrayType` or `GdDictionaryType`
/// - Array operands size equals source element count; Dictionary operands are key0/value0/key1/value1
/// - may carry `REJECT` decisions and `DuplicateKeyIssue`s without being a diagnostic
/// - never carries `GdCompilerType`
///
/// @param resultType         generic or contextual construction type of the literal root
/// @param operands           source-order operand materialization plans
/// @param duplicateKeyIssues frozen directly-reducible constant duplicate dictionary keys
public record FrontendContainerLiteralPlan(
        @NotNull GdContainerType resultType,
        @NotNull List<OperandPlan> operands,
        @NotNull List<DuplicateKeyIssue> duplicateKeyIssues
) {
    public enum OperandRole {
        ARRAY_ELEMENT,
        DICTIONARY_KEY,
        DICTIONARY_VALUE
    }

    /// One element / key / value boundary from a typed (or generic) container literal.
    ///
    /// @param sourceIndex zero-based element index for arrays; zero-based entry index for dictionary keys/values
    /// @param role        operand role inside the container
    /// @param sourceType  published child expression type
    /// @param targetType  element/key/value slot type used for construction
    /// @param decision    ordinary frontend boundary decision for {@code sourceType -> targetType}
    public record OperandPlan(
            int sourceIndex,
            @NotNull OperandRole role,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull FrontendVariantBoundaryCompatibility.Decision decision
    ) {
        public OperandPlan {
            if (sourceIndex < 0) {
                throw new IllegalArgumentException("sourceIndex must be non-negative");
            }
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(sourceType, "sourceType must not be null");
            Objects.requireNonNull(targetType, "targetType must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
        }
    }

    /// Constant-key collision frozen at plan construction time for later type-check reporting.
    ///
    /// @param firstEntryIndex     first entry that introduced the key
    /// @param duplicateEntryIndex later entry that collides with the first
    /// @param keyDisplay          human-readable key text for diagnostics
    public record DuplicateKeyIssue(
            int firstEntryIndex,
            int duplicateEntryIndex,
            @NotNull String keyDisplay
    ) {
        public DuplicateKeyIssue {
            if (firstEntryIndex < 0 || duplicateEntryIndex < 0) {
                throw new IllegalArgumentException("entry indices must be non-negative");
            }
            if (duplicateEntryIndex <= firstEntryIndex) {
                throw new IllegalArgumentException("duplicateEntryIndex must be greater than firstEntryIndex");
            }
            Objects.requireNonNull(keyDisplay, "keyDisplay must not be null");
            if (keyDisplay.isBlank()) {
                throw new IllegalArgumentException("keyDisplay must not be blank");
            }
        }
    }

    public FrontendContainerLiteralPlan {
        Objects.requireNonNull(resultType, "resultType must not be null");
        if (!(resultType instanceof GdArrayType) && !(resultType instanceof GdDictionaryType)) {
            throw new IllegalArgumentException(
                    "resultType must be GdArrayType or GdDictionaryType, got " + resultType.getTypeName()
            );
        }
        operands = List.copyOf(Objects.requireNonNull(operands, "operands must not be null"));
        duplicateKeyIssues = List.copyOf(
                Objects.requireNonNull(duplicateKeyIssues, "duplicateKeyIssues must not be null")
        );
    }

    /// Logical equivalence for idempotent merge / conflict checks on the published side table.
    ///
    /// Side-table key identity is already expression identity, so this compares payload only:
    /// result type by class+name, operands and duplicate issues by structural value equality.
    public static boolean samePlan(
            @NotNull FrontendContainerLiteralPlan first,
            @NotNull FrontendContainerLiteralPlan second
    ) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        if (!FrontendAnalysisData.sameType(first.resultType(), second.resultType())) {
            return false;
        }
        if (first.operands().size() != second.operands().size()
                || first.duplicateKeyIssues().size() != second.duplicateKeyIssues().size()) {
            return false;
        }
        for (var i = 0; i < first.operands().size(); i++) {
            if (!sameOperand(first.operands().get(i), second.operands().get(i))) {
                return false;
            }
        }
        for (var i = 0; i < first.duplicateKeyIssues().size(); i++) {
            if (!sameDuplicateIssue(first.duplicateKeyIssues().get(i), second.duplicateKeyIssues().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOperand(@NotNull OperandPlan first, @NotNull OperandPlan second) {
        return first.sourceIndex() == second.sourceIndex()
                && first.role() == second.role()
                && FrontendAnalysisData.sameType(first.sourceType(), second.sourceType())
                && FrontendAnalysisData.sameType(first.targetType(), second.targetType())
                && first.decision() == second.decision();
    }

    private static boolean sameDuplicateIssue(
            @NotNull DuplicateKeyIssue first,
            @NotNull DuplicateKeyIssue second
    ) {
        return first.firstEntryIndex() == second.firstEntryIndex()
                && first.duplicateEntryIndex() == second.duplicateEntryIndex()
                && first.keyDisplay().equals(second.keyDisplay());
    }
}
