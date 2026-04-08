package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVectorType;
import org.jetbrains.annotations.NotNull;

public final class FrontendSubscriptInsnSupport {
    private FrontendSubscriptInsnSupport() {
    }

    static void appendLoad(
            @NotNull LirBasicBlock block,
            @NotNull String resultSlotId,
            @NotNull String receiverSlotId,
            @NotNull GdType receiverType,
            @NotNull String keySlotId,
            @NotNull GdType keyType
    ) {
        appendLoad(
                block,
                resultSlotId,
                receiverSlotId,
                keySlotId,
                determineAccessKind(receiverType, keyType)
        );
    }

    static void appendLoad(
            @NotNull LirBasicBlock block,
            @NotNull String resultSlotId,
            @NotNull String receiverSlotId,
            @NotNull String keySlotId,
            @NotNull SubscriptAccessKind accessKind
    ) {
        switch (accessKind) {
            case GENERIC -> block.appendNonTerminatorInstruction(new VariantGetInsn(
                    resultSlotId,
                    receiverSlotId,
                    keySlotId
            ));
            case KEYED -> block.appendNonTerminatorInstruction(new VariantGetKeyedInsn(
                    resultSlotId,
                    receiverSlotId,
                    keySlotId
            ));
            case NAMED -> block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                    resultSlotId,
                    receiverSlotId,
                    keySlotId
            ));
            case INDEXED -> block.appendNonTerminatorInstruction(new VariantGetIndexedInsn(
                    resultSlotId,
                    receiverSlotId,
                    keySlotId
            ));
        }
    }

    static void appendStore(
            @NotNull LirBasicBlock block,
            @NotNull String receiverSlotId,
            @NotNull GdType receiverType,
            @NotNull String keySlotId,
            @NotNull GdType keyType,
            @NotNull String rhsSlotId
    ) {
        appendStore(
                block,
                receiverSlotId,
                keySlotId,
                rhsSlotId,
                determineAccessKind(receiverType, keyType)
        );
    }

    static void appendStore(
            @NotNull LirBasicBlock block,
            @NotNull String receiverSlotId,
            @NotNull String keySlotId,
            @NotNull String rhsSlotId,
            @NotNull SubscriptAccessKind accessKind
    ) {
        switch (accessKind) {
            case GENERIC -> block.appendNonTerminatorInstruction(new VariantSetInsn(
                    receiverSlotId,
                    keySlotId,
                    rhsSlotId
            ));
            case KEYED -> block.appendNonTerminatorInstruction(new VariantSetKeyedInsn(
                    receiverSlotId,
                    keySlotId,
                    rhsSlotId
            ));
            case NAMED -> block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                    receiverSlotId,
                    keySlotId,
                    rhsSlotId
            ));
            case INDEXED -> block.appendNonTerminatorInstruction(new VariantSetIndexedInsn(
                    receiverSlotId,
                    keySlotId,
                    rhsSlotId
            ));
        }
    }

    /// Resolves the already-frozen subscript instruction family from published base/key types.
    /// Shared writable-route support reuses the same helper so read/write lowering and future
    /// reverse-commit logic stay on one access-kind contract.
    static @NotNull SubscriptAccessKind determineAccessKind(
            @NotNull GdType receiverType,
            @NotNull GdType keyType
    ) {
        if (keyType instanceof GdIntType && supportsIndexedSubscript(receiverType)) {
            return SubscriptAccessKind.INDEXED;
        }
        if (keyType instanceof GdStringNameType && supportsNamedSubscript(receiverType)) {
            return SubscriptAccessKind.NAMED;
        }
        if (!(keyType instanceof GdVariantType) && supportsKeyedSubscript(receiverType)) {
            return SubscriptAccessKind.KEYED;
        }
        return SubscriptAccessKind.GENERIC;
    }

    private static boolean supportsKeyedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType;
    }

    private static boolean supportsNamedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType;
    }

    private static boolean supportsIndexedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdArrayType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType
                || receiverType instanceof GdPackedArrayType;
    }

    enum SubscriptAccessKind {
        GENERIC,
        KEYED,
        NAMED,
        INDEXED
    }
}
