package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.regex.Pattern;

public final class LoadStaticInsnGen implements CInsnGen<LoadStaticInsn> {
    public static final @NotNull String GLOBAL_SCOPE_RECEIVER = "@GlobalScope";
    private static final @NotNull Pattern INTEGER_LITERAL_PATTERN = Pattern.compile("[+-]?\\d+");

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.LOAD_STATIC);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var resultId = insn.resultId();
        if (resultId == null || resultId.isBlank()) {
            throw bodyBuilder.invalidInsn("Load static instruction missing result variable ID");
        }

        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }

        var target = bodyBuilder.targetOfVar(resultVar);
        var classRegistry = bodyBuilder.classRegistry();
        var className = insn.className();
        var staticName = insn.staticName();

        if (GLOBAL_SCOPE_RECEIVER.equals(className)) {
            var singletonType = classRegistry.findSingletonType(staticName);
            if (singletonType != null) {
                if (!classRegistry.checkAssignable(singletonType, resultVar.type())) {
                    throw bodyBuilder.invalidInsn(
                            "Static load target type '" + resultVar.type().getTypeName()
                                    + "' is not assignable from singleton type '" + singletonType.getTypeName() + "'"
                    );
                }
                var binding = ModuleLocalGodotBinding.singleton(staticName, singletonType.getTypeName());
                bodyBuilder.recordModuleLocalGodotBinding(binding);
                bodyBuilder.recordUsedGodotBindingCall(binding.cFunctionName());
                bodyBuilder.assignExpr(
                        target,
                        binding.cFunctionName() + "()",
                        singletonType,
                        CBodyBuilder.PtrKind.GODOT_PTR
                );
                return;
            }
            if (!classRegistry.checkAssignable(GdIntType.INT, resultVar.type())) {
                throw bodyBuilder.invalidInsn(
                        "Static load target type '" + resultVar.type().getTypeName() +
                                "' is not assignable from global constant"
                );
            }
            bodyBuilder.assignGlobalConstant(target, staticName);
            return;
        }

        if (classRegistry.findGlobalEnum(className) != null) {
            bodyBuilder.assignGlobalConst(target, className, staticName);
            return;
        }

        var builtinClass = classRegistry.findBuiltinClass(className);
        if (builtinClass != null) {
            var constantLookup = classRegistry.findBuiltinClassConstantInHierarchy(className, staticName);
            if (constantLookup != null) {
                var constant = constantLookup.constant();
                var constantOwnerName = constantLookup.ownerClass().name();
                var declaredType = parseBuiltinConstantType(bodyBuilder, constant, constantOwnerName, staticName);
                if (!classRegistry.checkAssignable(declaredType, resultVar.type())) {
                    throw bodyBuilder.invalidInsn(
                            "Static load target type '" + resultVar.type().getTypeName() +
                                    "' is not assignable from builtin constant type '" + declaredType.getTypeName() + "'"
                    );
                }
                var literalValue = constant.value();
                if (literalValue == null || literalValue.isBlank()) {
                    throw bodyBuilder.invalidInsn(
                            "Builtin constant '" + staticName + "' not found in class '" + className + "'"
                    );
                }
                bodyBuilder.helper().builtinBuilder().materializeStaticLiteralValue(
                        bodyBuilder,
                        target,
                        literalValue,
                        constantOwnerName,
                        staticName
                );
                return;
            }
            var enumValueLookup = classRegistry.findBuiltinClassEnumValueInHierarchy(className, staticName);
            if (enumValueLookup != null) {
                if (!classRegistry.checkAssignable(GdIntType.INT, resultVar.type())) {
                    throw bodyBuilder.invalidInsn(
                            "Static load target type '" + resultVar.type().getTypeName()
                                    + "' is not assignable from builtin class enum value"
                    );
                }
                bodyBuilder.assignExpr(target, Long.toString(enumValueLookup.enumValue().value()), GdIntType.INT);
                return;
            }
            throw bodyBuilder.invalidInsn(
                    "Builtin constant or enum value '" + staticName
                            + "' not found in class '" + className + "'"
            );
        }

        var classDef = classRegistry.getClassDef(new GdObjectType(className));
        if (classDef instanceof ExtensionGdClass engineClass) {
            var engineConstantLookup = classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), staticName);
            if (engineConstantLookup == null) {
                var enumValueLookup = classRegistry.findEngineClassEnumValueInHierarchy(className, staticName);
                if (enumValueLookup != null) {
                    if (!classRegistry.checkAssignable(GdIntType.INT, resultVar.type())) {
                        throw bodyBuilder.invalidInsn(
                                "Static load target type '" + resultVar.type().getTypeName()
                                        + "' is not assignable from engine class enum value"
                        );
                    }
                    bodyBuilder.assignExpr(target, Long.toString(enumValueLookup.enumValue().value()), GdIntType.INT);
                    return;
                }
                throw bodyBuilder.invalidInsn(
                        "Engine class constant or enum value '" + staticName
                                + "' not found in class '" + className + "' or its superclasses"
                );
            }
            var engineConstant = engineConstantLookup.constant();
            if (!classRegistry.checkAssignable(GdIntType.INT, resultVar.type())) {
                throw bodyBuilder.invalidInsn(
                        "Static load target type '" + resultVar.type().getTypeName() +
                                "' is not assignable from engine class integer constant"
                );
            }
            var literal = engineConstant.value() == null ? "" : engineConstant.value().trim();
            if (!INTEGER_LITERAL_PATTERN.matcher(literal).matches()) {
                throw bodyBuilder.invalidInsn(
                        "Engine class constant '" + staticName + "' in class '" + engineConstantLookup.ownerClass().getName() +
                                "' is not an integer literal: '" + engineConstant.value() + "'"
                );
            }
            bodyBuilder.assignExpr(target, literal, GdIntType.INT);
            return;
        }

        throw bodyBuilder.invalidInsn(
                "Static load target '" + className + "." + staticName +
                        "' is unsupported; only @GlobalScope global constants, global enums, builtin constants,"
                        + " builtin class enum values, engine class integer constants, and engine class enum values"
                        + " are allowed"
        );
    }

    private @NotNull GdType parseBuiltinConstantType(@NotNull CBodyBuilder bodyBuilder,
                                                     @NotNull ExtensionBuiltinClass.ConstantInfo constant,
                                                     @NotNull String className,
                                                     @NotNull String staticName) {
        var constantTypeName = constant.type();
        if (constantTypeName == null || constantTypeName.isBlank()) {
            throw bodyBuilder.invalidInsn(
                    "Builtin constant '" + staticName + "' in class '" + className + "' has no declared type"
            );
        }
        var parsedType = bodyBuilder.classRegistry().tryResolveDeclaredType(constantTypeName);
        if (parsedType == null) {
            throw bodyBuilder.invalidInsn(
                    "Builtin constant '" + staticName + "' in class '" + className +
                            "' has unsupported declared type '" + constantTypeName + "'"
            );
        }
        return parsedType;
    }
}
