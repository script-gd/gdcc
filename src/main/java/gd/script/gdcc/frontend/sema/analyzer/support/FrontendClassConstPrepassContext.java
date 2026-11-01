package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.scope.GdScriptClassConstant;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Per-class name views shared by the class `const` initializer context and the enum pre-pass
/// adapter during the joint const/enum pre-pass.
///
/// The views are split by purpose on purpose: `usableConstants` is the live published table (a
/// name only becomes resolvable once its declaration fully succeeded), while
/// `declaredMemberNames` is the whole structural inventory of the class — including later,
/// failed, and non-constant declarations — and exists purely as a lookup mask so references can
/// return a terminal blocked answer instead of leaking to ancestor or global names.
///
/// @param usableConstants       live view of the class's published script-constant table; rows
///                              appended during the pre-pass are visible to later initializers
///                              immediately, which is what makes earlier-constant references work
/// @param declaredMemberNames   every member name this class declares (name -> first declaration
///                              node); lookup mask only, never a declaration-conflict source
/// @param rejectedConstNames    names of this class's constants whose initializers already
///                              failed; the pre-pass appends to this set as it goes, so a failed
///                              `const` stays terminal-blocked for every later statement
/// @param ancestorMemberBlocker structural script-ancestor + lazy engine member name blocker
public record FrontendClassConstPrepassContext(
        @NotNull List<? extends GdScriptClassConstant> usableConstants,
        @NotNull Map<String, Node> declaredMemberNames,
        @NotNull Set<String> rejectedConstNames,
        @NotNull FrontendAncestorMemberBlocker ancestorMemberBlocker
) {
    public FrontendClassConstPrepassContext {
        Objects.requireNonNull(usableConstants, "usableConstants must not be null");
        Objects.requireNonNull(declaredMemberNames, "declaredMemberNames must not be null");
        Objects.requireNonNull(rejectedConstNames, "rejectedConstNames must not be null");
        Objects.requireNonNull(ancestorMemberBlocker, "ancestorMemberBlocker must not be null");
    }
}
