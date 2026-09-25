package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;

/// Module-level Callable identity catalog. Collects every lambda and standalone custom
/// Callable the module can create, derives each one's stable `impl_key` and canonical
/// schema descriptor (capture layout + signature + abi version), and renders the
/// per-extension anchor token. The catalog is emitted at the top of `entry.c`: identity
/// structs feed both the creation sites (by symbol reference) and the module rebind table the
/// next library generation uses to rebind surviving Callables to new implementations.
public final class CHrxIdentityCatalog {

    /// HRX ABI version baked into every schema descriptor prefix (`gdcc-hrx:<N>;...`). MUST
    /// equal `GDCC_HRX_ABI_VERSION` in `gdcc_hrx.h`: if the two drift, a spec passing the
    /// runtime abi_version guard could still match a stale descriptor (or vice versa),
    /// silently voiding one of the two gates. A dedicated contract test pins this constant
    /// against the C header macro.
    public static final int HRX_ABI_VERSION = 2;
    private static final String SCHEMA_DESC_PREFIX = "gdcc-hrx:" + HRX_ABI_VERSION + ";";

    /// Template-facing identity struct data (`schemaBytes`/`fingerprintBytes` are unsigned
    /// 0..255 initializers for the emitted C arrays; `callSiteContextCString` is the escaped
    /// literal body of the normalized call-site context, or null → the template emits NULL
    /// (standalone Callable identities never carry one).
    public record HrxIdentityTemplateData(
            @NotNull String symbol,
            @NotNull String implKeyCString,
            @NotNull List<Integer> schemaBytes,
            @NotNull List<Integer> fingerprintBytes,
            int argumentCount,
            @Nullable String callSiteContextCString
    ) {
    }

    /// Template-facing rebind table row (`destroySymbol == null` renders `NULL`: standalone
    /// payloads are fixed-ABI shell metadata and never need schema-guarded destruction).
    public record HrxRebindTemplateData(
            @NotNull String identitySymbol,
            @NotNull String implSymbol,
            @Nullable String destroySymbol,
            @NotNull String isValidSymbol
    ) {
    }

    private final @NotNull String anchorTokenHex;
    private final @NotNull List<HrxIdentityTemplateData> identities;
    private final @NotNull List<HrxRebindTemplateData> rebindEntries;

    private CHrxIdentityCatalog(
            @NotNull String anchorTokenHex,
            @NotNull List<HrxIdentityTemplateData> identities,
            @NotNull List<HrxRebindTemplateData> rebindEntries
    ) {
        this.anchorTokenHex = anchorTokenHex;
        this.identities = identities;
        this.rebindEntries = rebindEntries;
    }

    public @NotNull String anchorTokenHex() {
        return anchorTokenHex;
    }

    public @NotNull List<HrxIdentityTemplateData> identities() {
        return identities;
    }

    public @NotNull List<HrxRebindTemplateData> rebindEntries() {
        return rebindEntries;
    }

    /// Deterministic identity-struct symbol for one standalone Callable identity, shared by
    /// the creation-site emission (`ConstructInsnGen`) and this catalog's definitions.
    public static @NotNull String standaloneIdentitySymbol(
            @NotNull StandaloneCallableKind kind,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var implKey = standaloneImplKey(kind, ownerName, callableName);
        var fingerprint = StringUtil.md5(implKey.getBytes(StandardCharsets.UTF_8));
        return "gdcc_hrx_identity_sa_" + kind.token().replaceAll("[^A-Za-z0-9_]", "_")
                + "_" + (ownerName.isEmpty() ? "global" : ownerName).replaceAll("[^A-Za-z0-9_]", "_")
                + "_" + callableName.replaceAll("[^A-Za-z0-9_]", "_")
                + "_" + StringUtil.toHex(fingerprint, 4);
    }

    /// Standalone `impl_key` shape (`standalone:<kind>:<owner>:<name>`; utility owners are
    /// the empty string, matching the interned-registry normalization).
    public static @NotNull String standaloneImplKey(
            @NotNull StandaloneCallableKind kind,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        return "standalone:" + kind.token() + ":" + ownerName + ":" + callableName;
    }

    /// Collects the catalog for one module. Lambdas MUST carry their frontend-derived source
    /// identity key (the frontend always publishes it for real source lambdas); a keyless
    /// lambda is a compiler bug and fails the build instead of silently producing an
    /// unrebindable Callable.
    public static @NotNull CHrxIdentityCatalog collect(
            @NotNull LirModule module,
            @NotNull CodegenContext ctx,
            @NotNull CGenHelper helper
    ) {
        var identities = new ArrayList<HrxIdentityTemplateData>();
        var rebindEntries = new ArrayList<HrxRebindTemplateData>();
        // Standalone identities are deduplicated by (kind, owner, name): many call sites may
        // share one identity, and the interning runtime keys on exactly this triple.
        var standaloneSeen = new LinkedHashMap<String, StandaloneCallableSpecSupport.StandaloneCallableSpec>();
        var standaloneKinds = new LinkedHashMap<String, StandaloneCallableKind>();

        for (var classDef : module.getClassDefs()) {
            for (var function : classDef.getFunctions()) {
                collectStandaloneUsages(function, ctx, standaloneSeen, standaloneKinds);
                if (!function.isLambda()) {
                    continue;
                }
                var lambdaMeta = function.getLambdaMeta();
                if (lambdaMeta == null) {
                    throw new IllegalStateException(
                            "Lambda function '" + classDef.getName() + "." + function.getName()
                                    + "' has no lambda meta (source identity key); the frontend must publish"
                                    + " FrontendLambdaPlan.sourceIdentityKey() for every lambda"
                    );
                }
                var implKey = lambdaMeta.sourceIdentityKey();
                var symbol = helper.renderLambdaHrxIdentitySymbol(classDef, function);
                var schemaDesc = buildLambdaSchemaDesc(helper, function);
                var fingerprint = StringUtil.md5(schemaDesc.getBytes(StandardCharsets.UTF_8));
                identities.add(new HrxIdentityTemplateData(
                        symbol,
                        StringUtil.escapeStringLiteral(implKey),
                        toUnsignedBytes(schemaDesc.getBytes(StandardCharsets.UTF_8)),
                        toUnsignedBytes(fingerprint),
                        function.getParameterCount(),
                        // Lambdas always carry the frontend-computed descriptor; a null here means
                        // a hand-built LIR fixture (tolerated: the runtime gate is NULL-safe).
                        lambdaMeta.callSiteContext() == null
                                ? null
                                : StringUtil.escapeStringLiteral(lambdaMeta.callSiteContext())
                ));
                rebindEntries.add(new HrxRebindTemplateData(
                        symbol,
                        helper.renderLambdaCallFuncName(classDef, function),
                        helper.renderLambdaFreeFuncName(classDef, function),
                        helper.renderLambdaIsValidFuncName(classDef, function)
                ));
            }
        }

        for (var entry : standaloneSeen.entrySet()) {
            var identityKey = entry.getKey();
            var spec = entry.getValue();
            var kind = standaloneKinds.get(identityKey);
            var symbol = standaloneIdentitySymbol(kind, spec.ownerName(), spec.callableName());
            var schemaDesc = buildStandaloneSchemaDesc(kind, spec);
            var fingerprint = StringUtil.md5(schemaDesc.getBytes(StandardCharsets.UTF_8));
            identities.add(new HrxIdentityTemplateData(
                    symbol,
                    StringUtil.escapeStringLiteral(identityKey),
                    toUnsignedBytes(schemaDesc.getBytes(StandardCharsets.UTF_8)),
                    toUnsignedBytes(fingerprint),
                    spec.argumentCount(),
                    // Standalone Callable identities never carry a call-site context.
                    null
            ));
            // The shared standalone runtime functions (gdcc_callable.h statics) are the impl;
            // destroy stays NULL per the fixed-ABI payload contract.
            rebindEntries.add(new HrxRebindTemplateData(
                    symbol,
                    "gdcc_standalone_callable_call",
                    null,
                    "gdcc_standalone_callable_is_valid"
            ));
        }

        return new CHrxIdentityCatalog(anchorTokenHex(module.getModuleName()), List.copyOf(identities), List.copyOf(rebindEntries));
    }

    private static void collectStandaloneUsages(
            @NotNull LirFunctionDef function,
            @NotNull CodegenContext ctx,
            @NotNull Map<String, StandaloneCallableSpecSupport.StandaloneCallableSpec> seen,
            @NotNull Map<String, StandaloneCallableKind> kinds
    ) {
        for (var block : function) {
            for (var insn : block.getInstructions()) {
                if (!(insn instanceof ConstructStandaloneCallableInsn standaloneInsn)) {
                    continue;
                }
                var kind = standaloneInsn.kind();
                var rawImplKey = standaloneImplKey(kind, standaloneInsn.ownerName(), standaloneInsn.callableName());
                StandaloneCallableSpecSupport.StandaloneCallableSpec spec;
                try {
                    spec = StandaloneCallableSpecSupport.resolve(
                            ctx.classRegistry(), kind, standaloneInsn.ownerName(), standaloneInsn.callableName());
                } catch (IllegalStateException e) {
                    throw new IllegalStateException(
                            "Failed to catalog standalone Callable identity '" + rawImplKey
                                    + "' in function '" + function.getName() + "': " + e.getMessage(),
                            e
                    );
                }
                // Everything downstream keys on the RESOLVED declaring-class owner: an
                // inherited static referenced through a subclass and through its declaring
                // class is ONE identity (one C symbol, one intern key, one rebind row).
                var implKey = standaloneImplKey(kind, spec.ownerName(), spec.callableName());
                if (seen.containsKey(implKey)) {
                    continue;
                }
                seen.put(implKey, spec);
                kinds.put(implKey, kind);
            }
        }
    }

    /// Lambda schema descriptor: canonical encoding of the capture layout + signature +
    /// abi version (never the body). Each field contributes its semantic type name AND its C
    /// storage type: the storage type alone no longer pins the representation — since the
    /// Variant-backed packed storage switch (packed_array_reference_semantics_plan.md §4.1),
    /// every Packed*Array family AND plain Variant share the `godot_Variant` C spelling, so a
    /// capture/param/return swapped between packed families (or to/from Variant) would otherwise
    /// keep the same fingerprint and hot-reload would rebind an old holder into an implementation
    /// that dereferences it through a different family's internal-pointer getter (engine-level UB).
    private static @NotNull String buildLambdaSchemaDesc(
            @NotNull CGenHelper helper,
            @NotNull LirFunctionDef function
    ) {
        var sb = new StringBuilder(SCHEMA_DESC_PREFIX).append("caps=");
        var first = true;
        for (var capture : function.getCaptureList()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(renderSchemaFieldType(helper, capture.getType()));
            first = false;
        }
        sb.append(";params=");
        first = true;
        for (var i = 0; i < function.getParameterCount(); i++) {
            if (!first) {
                sb.append(',');
            }
            sb.append(renderSchemaFieldType(helper, Objects.requireNonNull(function.getParameter(i)).type()));
            first = false;
        }
        sb.append(";ret=").append(renderSchemaFieldType(helper, function.getReturnType()));
        sb.append(";va=").append(function.isVararg() ? '1' : '0');
        sb.append(";co=").append(function.isCoroutine() ? '1' : '0');
        return sb.toString();
    }

    /// One schema field: `<semantic type name>@<C storage type>` — semantic name distinguishes
    /// families sharing a C storage spelling; C storage keeps typed-container element encodings.
    private static @NotNull String renderSchemaFieldType(@NotNull CGenHelper helper, @NotNull GdType type) {
        return type.getTypeName() + "@" + helper.renderGdTypeInC(type);
    }

    /// Standalone schema descriptor: the call metadata is the whole compatibility surface
    /// (identity lives in the impl_key); the payload layout itself is fixed ABI.
    private static @NotNull String buildStandaloneSchemaDesc(
            @NotNull StandaloneCallableKind kind,
            @NotNull StandaloneCallableSpecSupport.StandaloneCallableSpec spec
    ) {
        return SCHEMA_DESC_PREFIX + "sa;kind=" + kind.token()
                + ";argc=" + spec.argumentCount()
                + ";va=" + (spec.vararg() ? '1' : '0')
                + ";ret=" + (spec.returnsValue() ? '1' : '0')
                + ";uh=" + spec.utilityHash();
    }

    /// Per-extension stable 64-bit anchor token: derived from the module (extension output)
    /// name only — never from optimization/architecture (the same `.gdextension` must map to
    /// the same token across generations and build flavors, and different extensions must not
    /// collide). Only ever compared for equality, never dereferenced.
    private static @NotNull String anchorTokenHex(@NotNull String moduleName) {
        var digest = StringUtil.md5(moduleName.getBytes(StandardCharsets.UTF_8));
        long token = 0;
        for (var i = 0; i < 8; i++) {
            token |= ((long) digest[i] & 0xFFL) << (8 * i);
        }
        if (token == 0) {
            token = 1; // paranoia: a zero token must never escape into the engine binding API
        }
        return String.format("%016X", token);
    }

    private static @NotNull List<Integer> toUnsignedBytes(byte @NotNull [] bytes) {
        var result = new ArrayList<Integer>(bytes.length);
        for (var b : bytes) {
            result.add(b & 0xFF);
        }
        return List.copyOf(result);
    }
}
