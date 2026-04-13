package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CCodegenTest {
    @Test
    public void variantGetOpcodeIsRegisteredAndGeneratesBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("index_load_codegen");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("self", GdVariantType.VARIANT);
        func.createAndAddVariable("key", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantGetInsn("result", "self", "key"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("index_load_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_get(&$self, &$key"), body);
        assertTrue(body.contains("$result = godot_new_Variant_with_Variant"), body);
    }

    @Test
    public void variantSetOpcodeIsRegisteredAndGeneratesBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("index_store_codegen");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("self", GdVariantType.VARIANT);
        func.createAndAddVariable("key", GdVariantType.VARIANT);
        func.createAndAddVariable("value", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantSetInsn("self", "key", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("index_store_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_set(&$self, &$key, &$value"), body);
    }

    @Test
    public void binaryOperatorOpcodeIsRegisteredAndFailFastIsControlled() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_fail_fast");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdIntType.INT);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator path is not implemented"));
    }

    @Test
    public void generatesEntryFiles() throws Exception {
        // build a simple LirModule
        var rotatingCameraClass = new LirClassDef("GDRotatingCamera3D", "Camera3D");
        rotatingCameraClass.addProperty(new LirPropertyDef("pitch_degree", GdFloatType.FLOAT));
        var module = new LirModule("my_module", List.of(rotatingCameraClass));

        // load extension API and class registry
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);

        // tiny ProjectInfo implementation for test
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        assertEquals(2, files.size(), "Should produce two files");

        var cFile = files.get(0);
        var hFile = files.get(1);
        var cCode = new String(cFile.contentWriter());
        var hCode = new String(hFile.contentWriter());
        System.out.println(hCode);
        System.out.println(cCode);
        assertTrue(cCode.contains("Loading my_module"));
        assertTrue(hCode.contains("GDEXTENSION_MY_MODULE_ENTRY_H"));
    }

    @Test
    public void generatesVariantMethodBindingMetadataAndKeepsNonVariantGate() throws Exception {
        var workerClass = new LirClassDef("VariantAbiWorker", "Node");

        var acceptVariant = new LirFunctionDef("accept_variant");
        acceptVariant.setReturnType(GdIntType.INT);
        acceptVariant.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, acceptVariant));
        acceptVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, acceptVariant));
        var acceptResult = acceptVariant.createAndAddTmpVariable(GdIntType.INT);
        var acceptEntry = new LirBasicBlock("entry");
        acceptEntry.appendInstruction(new LiteralIntInsn(acceptResult.id(), 1));
        acceptEntry.setTerminator(new ReturnInsn(acceptResult.id()));
        acceptVariant.addBasicBlock(acceptEntry);
        acceptVariant.setEntryBlockId("entry");
        workerClass.addFunction(acceptVariant);

        var echoVariant = new LirFunctionDef("echo_variant");
        echoVariant.setReturnType(GdVariantType.VARIANT);
        echoVariant.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, echoVariant));
        echoVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, echoVariant));
        var echoEntry = new LirBasicBlock("entry");
        echoEntry.setTerminator(new ReturnInsn("value"));
        echoVariant.addBasicBlock(echoEntry);
        echoVariant.setEntryBlockId("entry");
        workerClass.addFunction(echoVariant);

        var acceptInt = new LirFunctionDef("accept_int");
        acceptInt.setReturnType(GdIntType.INT);
        acceptInt.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, acceptInt));
        acceptInt.addParameter(new LirParameterDef("value", GdIntType.INT, null, acceptInt));
        var acceptIntEntry = new LirBasicBlock("entry");
        acceptIntEntry.setTerminator(new ReturnInsn("value"));
        acceptInt.addBasicBlock(acceptIntEntry);
        acceptInt.setEntryBlockId("entry");
        workerClass.addFunction(acceptInt);

        var module = new LirModule("variant_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = new String(files.getLast().contentWriter());

        var acceptVariantBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Variant_ret_int");
        var echoVariantBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Variant_ret_Variant");
        var acceptVariantCallBody = resolveCallWrapperBody(hCode, "_1_arg_Variant_ret_int");
        var acceptIntCallBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");

        assertContainsAll(
                acceptVariantBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
        );
        assertContainsAll(
                echoVariantBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
        );
        assertFalse(acceptVariantCallBody.contains("expected = GDEXTENSION_VARIANT_TYPE_NIL;"), acceptVariantCallBody);
        assertContainsAll(acceptIntCallBody, "expected = GDEXTENSION_VARIANT_TYPE_INT;");
    }

    @Test
    public void generatesVariantPropertyBindingMetadataAndKeepsNonVariantPropertyShape() throws Exception {
        var workerClass = new LirClassDef("VariantPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef("hidden_payload", GdVariantType.VARIANT));
        workerClass.addProperty(new LirPropertyDef("visible_payload", GdVariantType.VARIANT, false, null, null, null, Map.of("export", "")));
        workerClass.addProperty(new LirPropertyDef("hidden_score", GdIntType.INT));
        workerClass.addProperty(new LirPropertyDef("visible_score", GdIntType.INT, false, null, null, null, Map.of("export", "")));

        var module = new LirModule("variant_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var hiddenPayloadBind = resolvePropertyBindCall(cCode, "hidden_payload");
        var visiblePayloadBind = resolvePropertyBindCall(cCode, "visible_payload");
        var hiddenScoreBind = resolvePropertyBindCall(cCode, "hidden_score");
        var visibleScoreBind = resolvePropertyBindCall(cCode, "visible_score");

        assertEquals(4, countOccurrences(cCode, "gdcc_bind_property_full("), cCode);
        assertFalse(cCode.contains("gdcc_bind_property(class_name,"), cCode);
        assertContainsAll(
                hiddenPayloadBind,
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                "_field_getter_hidden_payload",
                "_field_setter_hidden_payload"
        );
        assertContainsAll(
                visiblePayloadBind,
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                "_field_getter_visible_payload",
                "_field_setter_visible_payload"
        );
        assertContainsAll(
                hiddenScoreBind,
                "GDEXTENSION_VARIANT_TYPE_INT",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_NO_EDITOR",
                "_field_getter_hidden_score",
                "_field_setter_hidden_score"
        );
        assertContainsAll(
                visibleScoreBind,
                "GDEXTENSION_VARIANT_TYPE_INT",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_visible_score",
                "_field_setter_visible_score"
        );
    }

    @Test
    public void generatesTypedDictionaryMethodBindingMetadataAndKeepsGenericDictionaryPlain() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryAbiWorker", "Node");
        var typedDictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));
        var mixedDictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT);
        var genericDictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef("payload", typedDictionaryType, null, acceptTypedPayload));
        var acceptTypedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var acceptTypedEntry = new LirBasicBlock("entry");
        acceptTypedEntry.appendInstruction(new LiteralIntInsn(acceptTypedResult.id(), 1));
        acceptTypedEntry.setTerminator(new ReturnInsn(acceptTypedResult.id()));
        acceptTypedPayload.addBasicBlock(acceptTypedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var echoMixedPayload = new LirFunctionDef("echo_mixed_payload");
        echoMixedPayload.setReturnType(mixedDictionaryType);
        echoMixedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, echoMixedPayload));
        echoMixedPayload.addParameter(new LirParameterDef("payload", mixedDictionaryType, null, echoMixedPayload));
        var echoMixedEntry = new LirBasicBlock("entry");
        echoMixedEntry.setTerminator(new ReturnInsn("payload"));
        echoMixedPayload.addBasicBlock(echoMixedEntry);
        echoMixedPayload.setEntryBlockId("entry");
        workerClass.addFunction(echoMixedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdBoolType.BOOL);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef("payload", genericDictionaryType, null, acceptGenericPayload));
        var acceptGenericResult = acceptGenericPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var acceptGenericEntry = new LirBasicBlock("entry");
        acceptGenericEntry.appendInstruction(new LiteralBoolInsn(acceptGenericResult.id(), true));
        acceptGenericEntry.setTerminator(new ReturnInsn(acceptGenericResult.id()));
        acceptGenericPayload.addBasicBlock(acceptGenericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_dictionary_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var hCode = new String(files.getLast().contentWriter());

        // Method registration splits the outward contract across entry.c and entry.h:
        // entry.c passes the base variant type, while entry.h fixes hint/hint_string/class_name/usage.
        var typedBindCall = resolveMethodBindCall(cCode, "accept_typed_payload");
        var typedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_int");
        var mixedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_Dictionary");
        var genericBindCall = resolveMethodBindCall(cCode, "accept_generic_payload");
        var genericBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_bool");

        assertContainsAll(
                typedBindCall,
                "GD_STATIC_SN(u8\"accept_typed_payload\")",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY"
        );
        assertContainsAll(
                typedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        assertContainsAll(
                mixedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Variant\")"
        );
        assertContainsAll(
                genericBindCall,
                "GD_STATIC_SN(u8\"accept_generic_payload\")",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY"
        );
        assertContainsAll(genericBindBody, "gdcc_make_property_full(arg0_type, arg0_name", "godot_PROPERTY_HINT_NONE");
        assertFalse(genericBindBody.contains("godot_PROPERTY_HINT_DICTIONARY_TYPE"), genericBindBody);
        assertFalse(genericBindBody.contains("GD_STATIC_S(u8\"StringName;"), genericBindBody);
    }

    @Test
    public void generatesTypedDictionaryPropertyBindingMetadataAndKeepsGenericDictionaryPlain() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "typed_payload",
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "mixed_payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "generic_payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));

        var module = new LirModule("typed_dictionary_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var typedBind = resolvePropertyBindCall(cCode, "typed_payload");
        var mixedBind = resolvePropertyBindCall(cCode, "mixed_payload");
        var genericBind = resolvePropertyBindCall(cCode, "generic_payload");

        assertContainsAll(
                typedBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_typed_payload",
                "_field_setter_typed_payload"
        );
        assertContainsAll(
                mixedBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"Variant;PackedInt32Array\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_mixed_payload",
                "_field_setter_mixed_payload"
        );
        assertContainsAll(
                genericBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_NONE",
                "GD_STATIC_S(u8\"\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_generic_payload",
                "_field_setter_generic_payload"
        );
        assertFalse(genericBind.contains("godot_PROPERTY_HINT_DICTIONARY_TYPE"), genericBind);
    }

    @Test
    public void generatesTypedArrayMethodBindingMetadataAndKeepsGenericArrayPlain() throws Exception {
        var workerClass = new LirClassDef("TypedArrayAbiWorker", "Node");
        var typedStringArray = new GdArrayType(GdStringNameType.STRING_NAME);
        var typedPlainArray = new GdArrayType(new GdArrayType(GdVariantType.VARIANT));
        var genericArray = new GdArrayType(GdVariantType.VARIANT);

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef("payload", typedStringArray, null, acceptTypedPayload));
        var acceptTypedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var acceptTypedEntry = new LirBasicBlock("entry");
        acceptTypedEntry.appendInstruction(new LiteralIntInsn(acceptTypedResult.id(), 1));
        acceptTypedEntry.setTerminator(new ReturnInsn(acceptTypedResult.id()));
        acceptTypedPayload.addBasicBlock(acceptTypedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var echoPlainPayload = new LirFunctionDef("echo_plain_payload");
        echoPlainPayload.setReturnType(typedPlainArray);
        echoPlainPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, echoPlainPayload));
        echoPlainPayload.addParameter(new LirParameterDef("payload", typedPlainArray, null, echoPlainPayload));
        var echoPlainEntry = new LirBasicBlock("entry");
        echoPlainEntry.setTerminator(new ReturnInsn("payload"));
        echoPlainPayload.addBasicBlock(echoPlainEntry);
        echoPlainPayload.setEntryBlockId("entry");
        workerClass.addFunction(echoPlainPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdBoolType.BOOL);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef("payload", genericArray, null, acceptGenericPayload));
        var acceptGenericResult = acceptGenericPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var acceptGenericEntry = new LirBasicBlock("entry");
        acceptGenericEntry.appendInstruction(new LiteralBoolInsn(acceptGenericResult.id(), true));
        acceptGenericEntry.setTerminator(new ReturnInsn(acceptGenericResult.id()));
        acceptGenericPayload.addBasicBlock(acceptGenericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_array_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var hCode = new String(files.getLast().contentWriter());

        var typedBindCall = resolveMethodBindCall(cCode, "accept_typed_payload");
        var typedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_int");
        var plainBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_Array");
        var genericBindCall = resolveMethodBindCall(cCode, "accept_generic_payload");
        var genericBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_bool");

        assertContainsAll(
                typedBindCall,
                "GD_STATIC_SN(u8\"accept_typed_payload\")",
                "GDEXTENSION_VARIANT_TYPE_ARRAY"
        );
        assertContainsAll(
                typedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"StringName\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        assertContainsAll(
                plainBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Array\")"
        );
        assertContainsAll(
                genericBindCall,
                "GD_STATIC_SN(u8\"accept_generic_payload\")",
                "GDEXTENSION_VARIANT_TYPE_ARRAY"
        );
        assertContainsAll(genericBindBody, "gdcc_make_property_full(arg0_type, arg0_name", "godot_PROPERTY_HINT_NONE");
        assertFalse(genericBindBody.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), genericBindBody);
        assertFalse(genericBindBody.contains("GD_STATIC_S(u8\"StringName\")"), genericBindBody);
    }

    @Test
    public void generatesTypedArrayPropertyBindingMetadataAndKeepsGenericArrayPlain() throws Exception {
        var workerClass = new LirClassDef("TypedArrayPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "typed_payload",
                new GdArrayType(GdStringNameType.STRING_NAME),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "plain_nested_payload",
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT)),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "generic_payload",
                new GdArrayType(GdVariantType.VARIANT),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));

        var module = new LirModule("typed_array_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var typedBind = resolvePropertyBindCall(cCode, "typed_payload");
        var plainBind = resolvePropertyBindCall(cCode, "plain_nested_payload");
        var genericBind = resolvePropertyBindCall(cCode, "generic_payload");

        assertContainsAll(
                typedBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"StringName\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_typed_payload",
                "_field_setter_typed_payload"
        );
        assertContainsAll(
                plainBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Array\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_plain_nested_payload",
                "_field_setter_plain_nested_payload"
        );
        assertContainsAll(
                genericBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_NONE",
                "GD_STATIC_S(u8\"\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_generic_payload",
                "_field_setter_generic_payload"
        );
        assertFalse(genericBind.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), genericBind);
    }

    @Test
    public void generatesTypedArrayCallWrapperPreflightAndKeepsGenericArrayOnBaseGate() throws Exception {
        var workerClass = new LirClassDef("TypedArrayCallGuardWorker", "Node");

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(new GdObjectType("Node")),
                null,
                acceptTypedPayload
        ));
        var typedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var typedEntry = new LirBasicBlock("entry");
        typedEntry.appendInstruction(new LiteralIntInsn(typedResult.id(), 1));
        typedEntry.setTerminator(new ReturnInsn(typedResult.id()));
        acceptTypedPayload.addBasicBlock(typedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var acceptPackedPayload = new LirFunctionDef("accept_packed_payload");
        acceptPackedPayload.setReturnType(GdBoolType.BOOL);
        acceptPackedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptPackedPayload));
        acceptPackedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                null,
                acceptPackedPayload
        ));
        var packedResult = acceptPackedPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var packedEntry = new LirBasicBlock("entry");
        packedEntry.appendInstruction(new LiteralBoolInsn(packedResult.id(), true));
        packedEntry.setTerminator(new ReturnInsn(packedResult.id()));
        acceptPackedPayload.addBasicBlock(packedEntry);
        acceptPackedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptPackedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdFloatType.FLOAT);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(GdVariantType.VARIANT),
                null,
                acceptGenericPayload
        ));
        var genericResult = acceptGenericPayload.createAndAddTmpVariable(GdFloatType.FLOAT);
        var genericEntry = new LirBasicBlock("entry");
        genericEntry.appendInstruction(new LiteralFloatInsn(genericResult.id(), 1.5));
        genericEntry.setTerminator(new ReturnInsn(genericResult.id()));
        acceptGenericPayload.addBasicBlock(genericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_array_call_guard_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = new String(files.getLast().contentWriter());

        var typedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_int");
        var packedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_bool");
        var genericCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_float");

        assertContainsAll(
                typedCallBody,
                "if (type != GDEXTENSION_VARIANT_TYPE_ARRAY)",
                "godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_StringName probe0_class_name = godot_Array_get_typed_class_name(&probe0);",
                "godot_Variant probe0_script = godot_Array_get_typed_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, &probe0_script_is_null_result, &probe0_script_is_null_valid);",
                "const godot_bool probe0_script_is_null = probe0_script_is_null_valid && godot_new_bool_with_Variant(&probe0_script_is_null_result);",
                "typed_mismatch = !godot_StringName_op_equal_StringName(&probe0_class_name, GD_STATIC_SN(u8\"Node\")) || !probe0_script_is_null;",
                "godot_Variant_destroy(&probe0_script_is_null_result);",
                "godot_Variant_destroy(&probe0_script_nil);",
                "godot_Variant_destroy(&probe0_script);",
                "godot_StringName_destroy(&probe0_class_name);",
                "godot_Array_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_ARRAY;",
                "argument = 0;"
        );
        var typedProbeIndex = typedCallBody.indexOf("godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        var typedArgIndex = typedCallBody.indexOf("arg0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        assertTrue(typedProbeIndex >= 0, typedCallBody);
        assertTrue(typedArgIndex > typedProbeIndex, typedCallBody);
        assertFalse(typedCallBody.contains("godot_Array_is_same_typed"), typedCallBody);

        assertContainsAll(
                packedCallBody,
                "godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY;",
                "godot_Array_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_ARRAY;"
        );
        assertFalse(packedCallBody.contains("probe0_class_name"), packedCallBody);
        assertFalse(packedCallBody.contains("probe0_script"), packedCallBody);
        assertFalse(packedCallBody.contains("godot_Array_is_same_typed"), packedCallBody);

        assertContainsAll(
                genericCallBody,
                "if (type != GDEXTENSION_VARIANT_TYPE_ARRAY)",
                "godot_Array arg0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertEquals(
                1,
                countOccurrences(genericCallBody, "godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                genericCallBody
        );
        assertFalse(genericCallBody.contains("typed_mismatch"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array_get_typed_"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array_is_same_typed"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array probe0 ="), genericCallBody);
    }

    @Test
    public void generatesTypedDictionaryCallWrapperPreflightAndKeepsGenericDictionaryOnBaseGate() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryCallGuardWorker", "Node");

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")),
                null,
                acceptTypedPayload
        ));
        var typedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var typedEntry = new LirBasicBlock("entry");
        typedEntry.appendInstruction(new LiteralIntInsn(typedResult.id(), 1));
        typedEntry.setTerminator(new ReturnInsn(typedResult.id()));
        acceptTypedPayload.addBasicBlock(typedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var acceptMixedPayload = new LirFunctionDef("accept_mixed_payload");
        acceptMixedPayload.setReturnType(GdBoolType.BOOL);
        acceptMixedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptMixedPayload));
        acceptMixedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                null,
                acceptMixedPayload
        ));
        var mixedResult = acceptMixedPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var mixedEntry = new LirBasicBlock("entry");
        mixedEntry.appendInstruction(new LiteralBoolInsn(mixedResult.id(), true));
        mixedEntry.setTerminator(new ReturnInsn(mixedResult.id()));
        acceptMixedPayload.addBasicBlock(mixedEntry);
        acceptMixedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptMixedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdFloatType.FLOAT);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                null,
                acceptGenericPayload
        ));
        var genericResult = acceptGenericPayload.createAndAddTmpVariable(GdFloatType.FLOAT);
        var genericEntry = new LirBasicBlock("entry");
        genericEntry.appendInstruction(new LiteralFloatInsn(genericResult.id(), 1.5));
        genericEntry.setTerminator(new ReturnInsn(genericResult.id()));
        acceptGenericPayload.addBasicBlock(genericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_dictionary_call_guard_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = new String(files.getLast().contentWriter());

        var typedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_int");
        var mixedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_bool");
        var genericCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_float");

        assertContainsAll(
                typedCallBody,
                "if (type != GDEXTENSION_VARIANT_TYPE_DICTIONARY)",
                "godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = false;",
                "typed_mismatch = godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "typed_mismatch = godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_StringName probe0_value_class_name = godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Variant probe0_value_script = godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_Variant probe0_value_script_nil = godot_new_Variant_nil();",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, &probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);",
                "const godot_bool probe0_value_script_is_null = probe0_value_script_is_null_valid && godot_new_bool_with_Variant(&probe0_value_script_is_null_result);",
                "typed_mismatch = !godot_StringName_op_equal_StringName(&probe0_value_class_name, GD_STATIC_SN(u8\"Node\")) || !probe0_value_script_is_null;",
                "godot_Variant_destroy(&probe0_value_script_is_null_result);",
                "godot_Variant_destroy(&probe0_value_script_nil);",
                "godot_Variant_destroy(&probe0_value_script);",
                "godot_StringName_destroy(&probe0_value_class_name);",
                "godot_Dictionary_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;",
                "argument = 0;"
        );
        var typedProbeIndex = typedCallBody.indexOf("godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        var typedArgIndex = typedCallBody.indexOf("arg0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        assertTrue(typedProbeIndex >= 0, typedCallBody);
        assertTrue(typedArgIndex > typedProbeIndex, typedCallBody);
        assertFalse(typedCallBody.contains("goto "), typedCallBody);

        assertContainsAll(
                mixedCallBody,
                "godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "typed_mismatch = godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_NIL;",
                "typed_mismatch = godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY;",
                "godot_Dictionary_destroy(&probe0);"
        );
        assertFalse(mixedCallBody.contains("probe0_key_class_name"), mixedCallBody);
        assertFalse(mixedCallBody.contains("probe0_value_class_name"), mixedCallBody);
        assertFalse(mixedCallBody.contains("probe0_value_script"), mixedCallBody);
        assertFalse(mixedCallBody.contains("godot_Dictionary_is_same_typed"), mixedCallBody);

        assertContainsAll(
                genericCallBody,
                "if (type != GDEXTENSION_VARIANT_TYPE_DICTIONARY)",
                "godot_Dictionary arg0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertEquals(
                1,
                countOccurrences(genericCallBody, "godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                genericCallBody
        );
        assertFalse(genericCallBody.contains("typed_mismatch"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Dictionary_is_same_typed"), genericCallBody);
        assertFalse(genericCallBody.contains("expectedBase0"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Dictionary probe0 ="), genericCallBody);
    }

    @Test
    public void generatesCallFuncCleanupForDestroyableWrapperLocals() throws Exception {
        var workerClass = new LirClassDef("CallWrapperCleanupWorker", "Node");

        var echoString = new LirFunctionDef("echo_string");
        echoString.setReturnType(GdStringType.STRING);
        echoString.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, echoString));
        echoString.addParameter(new LirParameterDef("value", GdStringType.STRING, null, echoString));
        var echoStringEntry = new LirBasicBlock("entry");
        echoStringEntry.setTerminator(new ReturnInsn("value"));
        echoString.addBasicBlock(echoStringEntry);
        echoString.setEntryBlockId("entry");
        workerClass.addFunction(echoString);

        var arrayToBool = new LirFunctionDef("array_to_bool");
        arrayToBool.setReturnType(GdBoolType.BOOL);
        arrayToBool.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, arrayToBool));
        arrayToBool.addParameter(new LirParameterDef("value", new GdArrayType(GdVariantType.VARIANT), null, arrayToBool));
        var boolResult = arrayToBool.createAndAddTmpVariable(GdBoolType.BOOL);
        var arrayToBoolEntry = new LirBasicBlock("entry");
        arrayToBoolEntry.appendInstruction(new LiteralBoolInsn(boolResult.id(), true));
        arrayToBoolEntry.setTerminator(new ReturnInsn(boolResult.id()));
        arrayToBool.addBasicBlock(arrayToBoolEntry);
        arrayToBool.setEntryBlockId("entry");
        workerClass.addFunction(arrayToBool);

        var echoVariant = new LirFunctionDef("echo_variant");
        echoVariant.setReturnType(GdVariantType.VARIANT);
        echoVariant.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, echoVariant));
        echoVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, echoVariant));
        var echoVariantEntry = new LirBasicBlock("entry");
        echoVariantEntry.setTerminator(new ReturnInsn("value"));
        echoVariant.addBasicBlock(echoVariantEntry);
        echoVariant.setEntryBlockId("entry");
        workerClass.addFunction(echoVariant);

        var consumeString = new LirFunctionDef("consume_string");
        consumeString.setReturnType(GdVoidType.VOID);
        consumeString.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, consumeString));
        consumeString.addParameter(new LirParameterDef("value", GdStringType.STRING, null, consumeString));
        var consumeStringEntry = new LirBasicBlock("entry");
        consumeStringEntry.setTerminator(new ReturnInsn(null));
        consumeString.addBasicBlock(consumeStringEntry);
        consumeString.setEntryBlockId("entry");
        workerClass.addFunction(consumeString);

        var module = new LirModule("call_wrapper_cleanup_module", List.of(workerClass));
        var hCode = generateHeader(module);
        var echoStringWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_String_ret_String");
        var consumeStringWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_String_no_ret");

        assertEquals(2, countOccurrences(hCode, "godot_String_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_String_destroy(&r);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Array_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Variant_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Variant_destroy(&r);"), hCode);
        assertEquals(3, countOccurrences(hCode, "godot_Variant_destroy(&ret);"), hCode);
        assertFalse(hCode.contains("godot_bool_destroy(&r);"), hCode);
        assertTrue(consumeStringWrapperBody.contains("godot_String_destroy(&arg0);"), consumeStringWrapperBody);
        assertFalse(consumeStringWrapperBody.contains("godot_Variant_destroy(&ret);"), consumeStringWrapperBody);

        var copyIndex = echoStringWrapperBody.indexOf("godot_variant_new_copy(r_return, &ret);");
        var retDestroyIndex = echoStringWrapperBody.indexOf("godot_Variant_destroy(&ret);", copyIndex);
        var returnDestroyIndex = echoStringWrapperBody.indexOf("godot_String_destroy(&r);", retDestroyIndex);
        var argDestroyIndex = echoStringWrapperBody.indexOf("godot_String_destroy(&arg0);", returnDestroyIndex);
        assertTrue(copyIndex >= 0, echoStringWrapperBody);
        assertTrue(retDestroyIndex > copyIndex, echoStringWrapperBody);
        assertTrue(returnDestroyIndex > retDestroyIndex, echoStringWrapperBody);
        assertTrue(argDestroyIndex > returnDestroyIndex, echoStringWrapperBody);
    }

    @Test
    public void generatesCallFuncCleanupWithoutDestroyingObjectsOrPrimitives() throws Exception {
        var workerClass = new LirClassDef("CallWrapperCleanupNegativeWorker", "Node");

        var echoNode = new LirFunctionDef("echo_node");
        echoNode.setReturnType(new GdObjectType("Node"));
        echoNode.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupNegativeWorker"), null, echoNode));
        echoNode.addParameter(new LirParameterDef("value", new GdObjectType("Node"), null, echoNode));
        var echoNodeEntry = new LirBasicBlock("entry");
        echoNodeEntry.setTerminator(new ReturnInsn("value"));
        echoNode.addBasicBlock(echoNodeEntry);
        echoNode.setEntryBlockId("entry");
        workerClass.addFunction(echoNode);

        var echoInt = new LirFunctionDef("echo_int");
        echoInt.setReturnType(GdIntType.INT);
        echoInt.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupNegativeWorker"), null, echoInt));
        echoInt.addParameter(new LirParameterDef("value", GdIntType.INT, null, echoInt));
        var echoIntEntry = new LirBasicBlock("entry");
        echoIntEntry.setTerminator(new ReturnInsn("value"));
        echoInt.addBasicBlock(echoIntEntry);
        echoInt.setEntryBlockId("entry");
        workerClass.addFunction(echoInt);

        var module = new LirModule("call_wrapper_cleanup_negative_module", List.of(workerClass));
        var hCode = generateHeader(module);
        var echoNodeWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_Node_ret_Node");
        var echoIntWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");

        assertEquals(2, countOccurrences(hCode, "godot_Variant_destroy(&ret);"), hCode);
        assertTrue(echoNodeWrapperBody.contains("godot_Variant_destroy(&ret);"), echoNodeWrapperBody);
        assertTrue(echoIntWrapperBody.contains("godot_Variant_destroy(&ret);"), echoIntWrapperBody);
        assertFalse(echoNodeWrapperBody.contains("godot_object_destroy(&arg0);"), echoNodeWrapperBody);
        assertFalse(echoNodeWrapperBody.contains("godot_object_destroy(&r);"), echoNodeWrapperBody);
        assertFalse(echoIntWrapperBody.contains("godot_int_destroy(&arg0);"), echoIntWrapperBody);
        assertFalse(echoIntWrapperBody.contains("godot_int_destroy(&r);"), echoIntWrapperBody);
    }

    @Test
    public void generateCreatesDefaultPropertyInitHelperWhenInitFuncIsUnset() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef("ready_value", GdIntType.INT);
        workerClass.addProperty(property);
        var module = new LirModule("property_init_default_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertEquals("_field_init_ready_value", property.getInitFunc());
        var initFunc = assertInstanceOf(
                LirFunctionDef.class,
                workerClass.getFunctions().stream()
                        .filter(function -> function.getName().equals("_field_init_ready_value"))
                        .findFirst()
                        .orElseThrow()
        );
        assertTrue(initFunc.isHidden());
        assertTrue(initFunc.hasBasicBlock("entry"));
        assertEquals("__prepare__", initFunc.getEntryBlockId());
        assertTrue(initFunc.hasBasicBlock("__prepare__"));
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertContainsAll(constructorBody, "GDWorkerNode_class_apply_property_init_ready_value(self);");
        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(self)");
        assertFalse(applyHelperBody.contains("_field_setter_"), applyHelperBody);
        assertFalse(cCode.contains("GD_STATIC_SN(u8\"_field_init_ready_value\")"), cCode);
    }

    @Test
    public void generateAcceptsPropertyInitFunctionWithExecutableBody() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef("ready_value", GdIntType.INT, false, "_field_init_ready_value", null, null, Map.of());
        workerClass.addProperty(property);
        var initFunction = new LirFunctionDef("_field_init_ready_value");
        initFunction.setHidden(true);
        initFunction.setReturnType(GdIntType.INT);
        initFunction.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, initFunction));
        var result = initFunction.createAndAddTmpVariable(GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn(result.id(), 7));
        entry.setTerminator(new ReturnInsn(result.id()));
        initFunction.addBasicBlock(entry);
        initFunction.setEntryBlockId("entry");
        workerClass.addFunction(initFunction);
        var module = new LirModule("property_init_lowered_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");
        var initHelperBody = resolveFunctionBodyByPrefix(cCode, "godot_int GDWorkerNode__field_init_ready_value");

        assertTrue(cCode.contains("godot_int GDWorkerNode__field_init_ready_value("), cCode);
        assertTrue(cCode.contains("GDWorkerNode* $self"), cCode);
        assertContainsAll(initHelperBody, "$0 = 7;");
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertContainsAll(constructorBody, "GDWorkerNode_class_apply_property_init_ready_value(self);");
        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(self)");
        assertFalse(applyHelperBody.contains("_field_setter_"), applyHelperBody);
    }

    @Test
    void generateUsesDedicatedDirectFieldApplyHelpersForObjectAndScalarPropertyInit() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef("ready_value", GdIntType.INT));
        workerClass.addProperty(new LirPropertyDef("ready_node", new GdObjectType("Node")));
        var module = new LirModule("property_init_apply_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");

        assertContainsAll(
                constructorBody,
                "GDWorkerNode_class_apply_property_init_ready_value(self);",
                "GDWorkerNode_class_apply_property_init_ready_node(self);"
        );

        var intApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        var objectApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_node");
        assertContainsAll(intApplyBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(self)");
        assertContainsAll(objectApplyBody, "self->ready_node =", "GDWorkerNode__field_init_ready_node(self)");
        assertFalse(intApplyBody.contains("_field_setter_"), intApplyBody);
        assertFalse(objectApplyBody.contains("_field_setter_"), objectApplyBody);
        assertFalse(constructorBody.contains("self->ready_value ="), constructorBody);
        assertFalse(constructorBody.contains("self->ready_node ="), constructorBody);
    }

    @Test
    void generatePropertyInitApplyHelperConsumesFreshRefCountedResultWithoutExtraOwn() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef("ready_ref", new GdObjectType("RefCounted")));
        var module = new LirModule("property_init_refcounted_apply_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_ref");
        assertContainsAll(applyHelperBody, "self->ready_ref =", "GDWorkerNode__field_init_ready_ref(self)");
        assertFalse(applyHelperBody.contains("own_object(self->ready_ref);"), applyHelperBody);
        assertFalse(applyHelperBody.contains("try_own_object(self->ready_ref);"), applyHelperBody);
        assertFalse(applyHelperBody.contains("release_object("), applyHelperBody);
        assertFalse(applyHelperBody.contains("try_release_object("), applyHelperBody);
    }

    @Test
    void generatePropertyInitApplyHelperDoesNotReuseExplicitSetterRoute() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                "_field_getter_ready_value",
                "custom_ready_value_setter",
                Map.of()
        );
        workerClass.addProperty(property);

        var initFunction = new LirFunctionDef("_field_init_ready_value");
        initFunction.setHidden(true);
        initFunction.setReturnType(GdIntType.INT);
        initFunction.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, initFunction));
        var result = initFunction.createAndAddTmpVariable(GdIntType.INT);
        var initEntry = new LirBasicBlock("entry");
        initEntry.appendInstruction(new LiteralIntInsn(result.id(), 7));
        initEntry.setTerminator(new ReturnInsn(result.id()));
        initFunction.addBasicBlock(initEntry);
        initFunction.setEntryBlockId("entry");
        workerClass.addFunction(initFunction);

        var setter = new LirFunctionDef("custom_ready_value_setter");
        setter.setReturnType(GdVoidType.VOID);
        setter.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, setter));
        setter.addParameter(new LirParameterDef("value", GdIntType.INT, null, setter));
        var setterEntry = new LirBasicBlock("entry");
        setterEntry.setTerminator(new ReturnInsn(null));
        setter.addBasicBlock(setterEntry);
        setter.setEntryBlockId("entry");
        workerClass.addFunction(setter);

        var module = new LirModule("property_init_apply_helper_setter_boundary_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");

        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(self)");
        assertFalse(applyHelperBody.contains("custom_ready_value_setter"), applyHelperBody);
        assertFalse(constructorBody.contains("custom_ready_value_setter"), constructorBody);
        assertTrue(cCode.contains("GD_STATIC_SN(u8\"custom_ready_value_setter\")"), cCode);
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionIsMissing() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var module = new LirModule("property_init_missing_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode._field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionRemainsShellOnly() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var shellOnlyInit = new LirFunctionDef("_field_init_ready_value");
        shellOnlyInit.setHidden(true);
        shellOnlyInit.setReturnType(GdIntType.INT);
        shellOnlyInit.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, shellOnlyInit));
        workerClass.addFunction(shellOnlyInit);
        var module = new LirModule("property_init_shell_only_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode.ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("_field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("shell-only"), exception.getMessage());
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionSignatureIsNotInternalHelperShape() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var invalidInit = new LirFunctionDef("_field_init_ready_value");
        invalidInit.setHidden(true);
        invalidInit.setReturnType(GdFloatType.FLOAT);
        invalidInit.addParameter(new LirParameterDef("value", GdIntType.INT, null, invalidInit));
        var entry = new LirBasicBlock("entry");
        var result = invalidInit.createAndAddTmpVariable(GdFloatType.FLOAT);
        entry.appendInstruction(new LiteralFloatInsn(result.id(), 1.0));
        entry.setTerminator(new ReturnInsn(result.id()));
        invalidInit.addBasicBlock(entry);
        invalidInit.setEntryBlockId("entry");
        workerClass.addFunction(invalidInit);
        var module = new LirModule("property_init_invalid_signature_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode.ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("_field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("mismatched return type"), exception.getMessage());
    }

    @Test
    public void generatesMappedCanonicalClassNamesVerbatimInArtifacts() throws Exception {
        var runtimeOuterClass = new LirClassDef("RuntimeOuter", "Node");
        var module = new LirModule("mapped_runtime_module", List.of(runtimeOuterClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = new String(files.getFirst().contentWriter());
        var hCode = new String(files.getLast().contentWriter());

        assertTrue(hCode.contains("struct RuntimeOuter {"), hCode);
        assertTrue(hCode.contains("RuntimeOuter_class_create_instance"), hCode);
        assertTrue(cCode.contains("RuntimeOuter_class_create_instance"), cCode);
        assertTrue(cCode.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"Node\"))"), cCode);
        assertFalse(hCode.contains("MappedOuter"), hCode);
        assertFalse(cCode.contains("MappedOuter"), cCode);
    }

    @Test
    public void rendersOperatorEvaluatorHelpersAndUsesHelperCallsInFunctionBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_eval");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdIntType.INT);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("tmp", GdBoolType.BOOL);
        func.createAndAddVariable("result", GdBoolType.BOOL);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("tmp", GodotOperator.IN, "left", "right"));
        entry.appendInstruction(new UnaryOpInsn("result", GodotOperator.NOT, "tmp"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_eval_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(evaluatorIntApi());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = new String(files.getFirst().contentWriter());
        var hCode = new String(files.getLast().contentWriter());

        assertTrue(hCode.contains("static inline godot_bool gdcc_eval_binary_in_int_int_to_bool("), hCode);
        assertTrue(hCode.contains("static inline godot_bool gdcc_eval_unary_not_bool_to_bool("), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_IN"), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_NOT"), hCode);
        assertTrue(hCode.contains("GDCC_PRINT_RUNTIME_ERROR(\"operator evaluator is unavailable"), hCode);
        assertTrue(hCode.contains("return false;"), hCode);
        assertTrue(cCode.contains("$tmp = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), cCode);
        assertTrue(cCode.contains("$result = gdcc_eval_unary_not_bool_to_bool($tmp);"), cCode);
    }

    @Test
    public void codegenShouldFailWhenOnlySwappedMetadataExists() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_eval_swap");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdStringType.STRING);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("result", GdBoolType.BOOL);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_eval_swap_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(evaluatorSwapFallbackApi());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var ex = assertThrows(RuntimeException.class, codegen::generate);
        var rootCause = findRootCause(ex);
        assertTrue(
                rootCause.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"),
                rootCause.getMessage()
        );
    }

    @Test
    public void generatesExplicitGdccInheritanceLayoutAndObjectPtrHelpers() throws Exception {
        var parentClass = new LirClassDef("GDParentNode", "Node");
        parentClass.addProperty(new LirPropertyDef("speed", GdFloatType.FLOAT));

        var childClass = new LirClassDef("GDChildNode", "GDParentNode");
        childClass.addProperty(new LirPropertyDef("peer", new GdObjectType("GDParentNode")));

        var module = new LirModule("inheritance_layout_module", List.of(parentClass, childClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        var cCode = new String(files.get(0).contentWriter());
        var hCode = new String(files.get(1).contentWriter());
        var childObjectPtrHelperBody = resolveFunctionBodyByPrefix(
                cCode,
                "static inline GDExtensionObjectPtr GDChildNode_object_ptr"
        );
        var childCreateInstanceBody = resolveCreateInstanceBody(cCode, "GDChildNode");
        var childConstructorBody = resolveClassConstructorBody(cCode, "GDChildNode");
        var childDestructorBody = resolveClassDestructorBody(cCode, "GDChildNode");

        assertContainsAll(
                hCode,
                "struct GDParentNode {",
                "GDExtensionObjectPtr _object;",
                "struct GDChildNode {",
                "GDParentNode _super;",
                "GDParentNode_object_ptr(",
                "GDChildNode_object_ptr(",
                "GDChildNode_set_object_ptr("
        );
        assertContainsAll(childObjectPtrHelperBody, "return GDParentNode_object_ptr(&self->_super);");
        assertContainsAll(
                childCreateInstanceBody,
                "GDChildNode_set_object_ptr(self, obj);"
        );
        assertContainsAll(childConstructorBody, "GDParentNode_class_constructor(&self->_super);");
        assertContainsAll(childDestructorBody, "GDParentNode_class_destructor(&self->_super);");
        assertContainsAll(cCode, "try_release_object(GDParentNode_object_ptr(self->peer));");

        assertEquals("Node", resolveConstructTarget(cCode, "GDParentNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDChildNode"));
        var directParentConstructPattern = Pattern.compile(
                "GDExtensionObjectPtr\\s+GDChildNode_class_create_instance\\([^)]*\\)\\s*\\{\\s*GDExtensionObjectPtr obj = godot_classdb_construct_object2\\(GD_STATIC_SN\\(u8\"GDParentNode\"\\)\\);",
                Pattern.DOTALL);
        assertFalse(directParentConstructPattern.matcher(cCode).find());
    }

    @Test
    public void createInstanceUsesSingleBindingAndNearestNativeAncestorForDeepGdccInheritance() throws Exception {
        var rootClass = new LirClassDef("GDRootNode", "Node");
        var midClass = new LirClassDef("GDMidNode", "GDRootNode");
        var leafClass = new LirClassDef("GDLeafNode", "GDMidNode");
        var module = new LirModule("deep_inheritance_module", List.of(rootClass, midClass, leafClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertEquals("Node", resolveConstructTarget(cCode, "GDRootNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDMidNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDLeafNode"));

        var leafCreateInstanceBody = resolveCreateInstanceBody(cCode, "GDLeafNode");
        assertEquals(1, countOccurrences(leafCreateInstanceBody, "godot_object_set_instance("));
        assertEquals(1, countOccurrences(leafCreateInstanceBody, "godot_object_set_instance_binding("));
    }

    @Test
    public void createInstanceKeepsRawNativeConstructionForBothRefCountedAndPlainGdccClasses() throws Exception {
        var countedClass = new LirClassDef("GDCountedWorker", "RefCounted");
        var plainClass = new LirClassDef("GDPlainObject", "Object");
        var module = new LirModule("ref_counted_create_instance_module", List.of(countedClass, plainClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        var countedBody = resolveCreateInstanceBody(cCode, "GDCountedWorker");
        var plainBody = resolveCreateInstanceBody(cCode, "GDPlainObject");

        assertTrue(
                countedBody.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"RefCounted\"))"),
                countedBody
        );
        assertTrue(
                plainBody.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"Object\"))"),
                plainBody
        );
        assertFalse(countedBody.contains("gdcc_ref_counted_init_raw("), countedBody);
        assertFalse(plainBody.contains("gdcc_ref_counted_init_raw("), plainBody);
    }

    @Test
    void classConstructorShouldOnlyAutoInvokeZeroArgInit() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var init = new LirFunctionDef("_init");
        init.setReturnType(GdVoidType.VOID);
        init.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, init));
        init.addParameter(new LirParameterDef("value", GdIntType.INT, null, init));
        var initEntry = new LirBasicBlock("entry");
        init.addBasicBlock(initEntry);
        initEntry.setTerminator(new ReturnInsn(null));
        init.setEntryBlockId("entry");
        workerClass.addFunction(init);

        var zeroArgClass = new LirClassDef("GDZeroArgNode", "Node");
        var zeroArgInit = new LirFunctionDef("_init");
        zeroArgInit.setReturnType(GdVoidType.VOID);
        zeroArgInit.addParameter(new LirParameterDef("self", new GdObjectType("GDZeroArgNode"), null, zeroArgInit));
        var zeroArgEntry = new LirBasicBlock("entry");
        zeroArgInit.addBasicBlock(zeroArgEntry);
        zeroArgEntry.setTerminator(new ReturnInsn(null));
        zeroArgInit.setEntryBlockId("entry");
        zeroArgClass.addFunction(zeroArgInit);

        var module = new LirModule("constructor_init_codegen_module", List.of(workerClass, zeroArgClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertFalse(cCode.contains("GDWorkerNode__init(self);"), cCode);
        assertTrue(cCode.contains("GDZeroArgNode__init(self);"), cCode);
    }

    private static String resolveConstructTarget(String cCode, String className) {
        var functionPrefix = "GDExtensionObjectPtr\\s+" + Pattern.quote(className) + "_class_create_instance";
        var pattern = Pattern.compile(functionPrefix +
                        "\\([^)]*\\)\\s*\\{\\s*GDExtensionObjectPtr obj = godot_classdb_construct_object2\\(GD_STATIC_SN\\(u8\"([^\"]+)\"\\)\\);",
                Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing create_instance construct target for class " + className);
        return matcher.group(1);
    }

    private static String resolveCreateInstanceBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "GDExtensionObjectPtr " + className + "_class_create_instance");
    }

    private static String resolveClassConstructorBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "void " + className + "_class_constructor");
    }

    private static String resolveClassDestructorBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "void " + className + "_class_destructor");
    }

    private static String resolvePropertyInitApplyHelperBody(String cCode, String className, String propertyName) {
        return resolveFunctionBodyByPrefix(
                cCode,
                "static inline void " + className + "_class_apply_property_init_" + propertyName
        );
    }

    private static String resolveCallWrapperBody(String hCode, String bindName) {
        return resolveFunctionBodyByPrefix(hCode, "static void call" + bindName);
    }

    private static String resolveMethodBindHelperBody(String hCode, String bindName) {
        return resolveFunctionBodyByPrefix(hCode, "static void gdcc_bind_method" + bindName);
    }

    private static String resolveMethodBindCall(String cCode, String methodName) {
        var methodAnchor = "GD_STATIC_SN(u8\"" + methodName + "\")";
        var methodIndex = cCode.indexOf(methodAnchor);
        assertTrue(methodIndex >= 0, "Missing method binding anchor for " + methodName);
        var callStart = cCode.lastIndexOf("gdcc_bind_method", methodIndex);
        assertTrue(callStart >= 0, "Missing method binding call for " + methodName);
        var callEnd = cCode.indexOf(");", methodIndex);
        assertTrue(callEnd >= 0, "Missing end of method binding call for " + methodName);
        return cCode.substring(callStart, callEnd + 2);
    }

    private static String resolvePropertyBindCall(String cCode, String propertyName) {
        var propertyAnchor = "GD_STATIC_SN(u8\"" + propertyName + "\")";
        var propertyIndex = cCode.indexOf(propertyAnchor);
        assertTrue(propertyIndex >= 0, "Missing property binding anchor for " + propertyName);
        var callStart = cCode.lastIndexOf("gdcc_bind_property_full(", propertyIndex);
        assertTrue(callStart >= 0, "Missing full property binding call for " + propertyName);
        var callEnd = cCode.indexOf(");", propertyIndex);
        assertTrue(callEnd >= 0, "Missing end of property binding call for " + propertyName);
        return cCode.substring(callStart, callEnd + 2);
    }

    private static String resolveFunctionBodyByPrefix(String code, String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        var closeBraceIndex = findMatchingBrace(code, openBraceIndex);
        return code.substring(openBraceIndex + 1, closeBraceIndex);
    }

    private static int findMatchingBrace(String text, int openBraceIndex) {
        var depth = 0;
        for (var i = openBraceIndex; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("Missing closing brace for function body");
    }

    private static void assertContainsAll(String text, String... needles) {
        for (var needle : needles) {
            assertTrue(
                    text.contains(needle),
                    () -> "Missing fragment `" + needle + "` in:\n" + text
            );
        }
    }

    private static String generateHeader(LirModule module) throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        return new String(files.getLast().contentWriter());
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var fromIndex = 0;
        while (true) {
            var index = text.indexOf(needle, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + needle.length();
        }
    }

    private static Throwable findRootCause(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ExtensionAPI evaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var boolBuiltin = new ExtensionBuiltinClass(
                "bool",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("not", "", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin, boolBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionAPI evaluatorSwapFallbackApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("<", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
