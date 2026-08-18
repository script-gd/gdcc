package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlan;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Outer-body construction of a recorded lambda value.
///
/// The item carries the synthesized lambda function name plus the ordered capture operands read
/// from the ENCLOSING frame; the lambda body itself lowers separately through its own
/// `LAMBDA_BODY` context. Capture operands are direct enclosing-frame slot reads
/// modeled by `CaptureOperand` - a leading `self` capture uses the dedicated
/// `SelfSlotOperand.SELF_SLOT` descriptor instead of a fabricated `IdentifierExpression` + SELF
/// binding.
public record LambdaConstructItem(
        @NotNull LambdaExpression lambdaAnchor,
        @NotNull String lambdaName,
        @NotNull List<CaptureOperand> captureOperands,
        @NotNull String resultValueId
) implements ValueOpItem {

    public LambdaConstructItem {
        Objects.requireNonNull(lambdaAnchor, "lambdaAnchor must not be null");
        lambdaName = StringUtil.requireNonBlank(lambdaName, "lambdaName");
        captureOperands = List.copyOf(Objects.requireNonNull(captureOperands, "captureOperands must not be null"));
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return lambdaAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    /// Capture operands read enclosing-frame slots directly at construction time, so this item
    /// consumes no frontend value ids.
    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of();
    }

    /// One capture operand of a lambda construction, resolved against the enclosing frame.
    public sealed interface CaptureOperand {
        /// Enclosing-frame slot id read by this operand; by construction this always equals the
        /// capture entry name, which lets body lowering cross-check operand order against the
        /// synthesized shell's capture list.
        @NotNull String slotId();
    }

    /// Reads a named enclosing-frame slot (`LOCAL_VAR` / `PARAMETER` / outer `CAPTURE` source).
    public record VariableSlotOperand(@NotNull String slotId) implements CaptureOperand {
        public VariableSlotOperand {
            slotId = StringUtil.requireNonBlank(slotId, "slotId");
        }
    }

    /// The dedicated enclosing `self` slot descriptor; never fabricated as an identifier read.
    public enum SelfSlotOperand implements CaptureOperand {
        SELF_SLOT;

        @Override
        public @NotNull String slotId() {
            return FrontendLambdaCapturePlan.SELF_CAPTURE_NAME;
        }
    }
}
