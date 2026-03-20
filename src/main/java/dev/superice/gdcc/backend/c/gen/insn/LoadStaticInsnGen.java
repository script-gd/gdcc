package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.regex.Pattern;

public final class LoadStaticInsnGen implements CInsnGen<LoadStaticInsn> {
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

        if (classRegistry.findGlobalEnum(className) != null) {
            bodyBuilder.assignGlobalConst(target, className, staticName);
            return;
        }

        var builtinClass = classRegistry.findBuiltinClass(className);
        if (builtinClass != null) {
            var constant = findBuiltinConstant(bodyBuilder, builtinClass, staticName);
            var declaredType = parseBuiltinConstantType(bodyBuilder, constant, className, staticName);
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
                    className,
                    staticName
            );
            return;
        }

        var classDef = classRegistry.getClassDef(new GdObjectType(className));
        if (classDef instanceof ExtensionGdClass engineClass) {
            var engineConstant = engineClass.constants().stream()
                    .filter(constant -> staticName.equals(constant.name()))
                    .findFirst()
                    .orElse(null);
            if (engineConstant == null) {
                throw bodyBuilder.invalidInsn(
                        "Engine class constant '" + staticName + "' not found in class '" + className + "'"
                );
            }
            if (!classRegistry.checkAssignable(GdIntType.INT, resultVar.type())) {
                throw bodyBuilder.invalidInsn(
                        "Static load target type '" + resultVar.type().getTypeName() +
                                "' is not assignable from engine class integer constant"
                );
            }
            var literal = engineConstant.value() == null ? "" : engineConstant.value().trim();
            if (!INTEGER_LITERAL_PATTERN.matcher(literal).matches()) {
                throw bodyBuilder.invalidInsn(
                        "Engine class constant '" + staticName + "' in class '" + className +
                                "' is not an integer literal: '" + engineConstant.value() + "'"
                );
            }
            bodyBuilder.assignExpr(target, literal, GdIntType.INT);
            return;
        }

        throw bodyBuilder.invalidInsn(
                "Static load target '" + className + "." + staticName +
                        "' is unsupported; only global enums, builtin constants, and engine class integer constants are allowed"
        );
    }

    private @NotNull ExtensionBuiltinClass.ConstantInfo findBuiltinConstant(@NotNull CBodyBuilder bodyBuilder,
                                                                            @NotNull ExtensionBuiltinClass builtinClass,
                                                                            @NotNull String staticName) {
        var matchedConstant = builtinClass.constants().stream()
                .filter(constant -> staticName.equals(constant.name()))
                .findFirst()
                .orElse(null);
        if (matchedConstant == null) {
            throw bodyBuilder.invalidInsn(
                    "Builtin constant '" + staticName + "' not found in class '" + builtinClass.name() + "'"
            );
        }
        return matchedConstant;
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
