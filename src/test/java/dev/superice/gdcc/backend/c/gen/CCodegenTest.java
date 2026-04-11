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
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
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

        assertEquals(
                2,
                countOccurrences(
                        hCode,
                        "gdcc_make_property_full(arg0_type, arg0_name, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT)"
                ),
                hCode
        );
        assertEquals(
                1,
                countOccurrences(
                        hCode,
                        "GDExtensionPropertyInfo return_info = gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_NIL, GD_STATIC_SN(u8\"\"), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT);"
                ),
                hCode
        );
        assertFalse(hCode.contains("expected = GDEXTENSION_VARIANT_TYPE_NIL;"), hCode);
        assertEquals(1, countOccurrences(hCode, "expected = GDEXTENSION_VARIANT_TYPE_INT;"), hCode);
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
        assertTrue(
                cCode.contains("static inline void GDWorkerNode_class_apply_property_init_ready_value(GDWorkerNode* self)"),
                cCode
        );
        assertTrue(cCode.contains("GDWorkerNode_class_apply_property_init_ready_value(self);"), cCode);
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertTrue(applyHelperBody.contains("self->ready_value = GDWorkerNode__field_init_ready_value(self);"), applyHelperBody);
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

        assertTrue(cCode.contains("godot_int GDWorkerNode__field_init_ready_value("), cCode);
        assertTrue(cCode.contains("GDWorkerNode* $self"), cCode);
        assertTrue(cCode.contains("$0 = 7;"), cCode);
        assertTrue(cCode.contains("GDWorkerNode_class_apply_property_init_ready_value(self);"), cCode);
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertTrue(applyHelperBody.contains("self->ready_value = GDWorkerNode__field_init_ready_value(self);"), applyHelperBody);
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

        assertTrue(constructorBody.contains("GDWorkerNode_class_apply_property_init_ready_value(self);"), constructorBody);
        assertTrue(constructorBody.contains("GDWorkerNode_class_apply_property_init_ready_node(self);"), constructorBody);

        var intApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        var objectApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_node");
        assertTrue(intApplyBody.contains("self->ready_value = GDWorkerNode__field_init_ready_value(self);"), intApplyBody);
        assertTrue(objectApplyBody.contains("self->ready_node = GDWorkerNode__field_init_ready_node(self);"), objectApplyBody);
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
        assertTrue(
                applyHelperBody.contains("self->ready_ref = GDWorkerNode__field_init_ready_ref(self);"),
                applyHelperBody
        );
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

        assertTrue(applyHelperBody.contains("self->ready_value = GDWorkerNode__field_init_ready_value(self);"), applyHelperBody);
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

        assertTrue(hCode.contains("struct GDParentNode {"));
        assertTrue(hCode.contains("GDExtensionObjectPtr _object;"));
        assertTrue(hCode.contains("struct GDChildNode {"));
        assertTrue(hCode.contains("GDParentNode _super;"));
        assertTrue(hCode.contains("static inline GDExtensionObjectPtr GDParentNode_object_ptr(GDParentNode* self);"));
        assertTrue(hCode.contains("static inline GDExtensionObjectPtr GDChildNode_object_ptr(GDChildNode* self);"));
        assertTrue(hCode.contains("static inline void GDChildNode_set_object_ptr(GDChildNode* self, GDExtensionObjectPtr obj);"));

        assertTrue(cCode.contains("static inline GDExtensionObjectPtr GDChildNode_object_ptr(GDChildNode* self)"));
        assertTrue(cCode.contains("return GDParentNode_object_ptr(&self->_super);"));
        assertTrue(cCode.contains("GDChildNode_set_object_ptr(self, obj);"));
        assertTrue(cCode.contains("GDParentNode_class_constructor(&self->_super);"));
        assertTrue(cCode.contains("try_release_object(GDParentNode_object_ptr(self->peer));"));
        assertTrue(cCode.contains("GDParentNode_class_destructor(&self->_super);"));

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
        var functionPrefix = "GDExtensionObjectPtr\\s+" + Pattern.quote(className) + "_class_create_instance";
        var pattern = Pattern.compile(functionPrefix + "\\([^)]*\\)\\s*\\{(.*?)return obj;\\s*}", Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing create_instance body for class " + className);
        return matcher.group(1);
    }

    private static String resolveClassConstructorBody(String cCode, String className) {
        var functionPrefix = "void\\s+" + Pattern.quote(className) + "_class_constructor";
        var pattern = Pattern.compile(functionPrefix + "\\([^)]*\\)\\s*\\{(.*?)\\n}", Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing class_constructor body for class " + className);
        return matcher.group(1);
    }

    private static String resolvePropertyInitApplyHelperBody(String cCode, String className, String propertyName) {
        var functionPrefix = "static inline void\\s+"
                + Pattern.quote(className)
                + "_class_apply_property_init_"
                + Pattern.quote(propertyName);
        var pattern = Pattern.compile(functionPrefix + "\\([^)]*\\)\\s*\\{(.*?)\\n}", Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(
                matcher.find(),
                "Missing property-init apply helper for " + className + "." + propertyName
        );
        return matcher.group(1);
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
