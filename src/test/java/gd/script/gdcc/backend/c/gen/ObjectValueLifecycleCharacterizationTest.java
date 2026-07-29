package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.TryOwnObjectInsn;
import gd.script.gdcc.lir.insn.TryReleaseObjectInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Characterization for object lifecycle, conversion, comparison and Variant boundaries.
///
/// Positive paths freeze slot-write order, C1 equality, pack/unpack helpers and RefCounted matrix
/// under internal fat pointers. Negative paths reject unknown object surfaces and keep lifecycle
/// helpers on validated live raw + cached `instance_id` (never recover ID from a freed raw).
class ObjectValueLifecycleCharacterizationTest {
    private static final GdObjectType ENGINE_OBJECT = new GdObjectType("Object");
    private static final GdObjectType ENGINE_NODE = new GdObjectType("Node");
    private static final GdObjectType ENGINE_REFCOUNTED = new GdObjectType("RefCounted");
    private static final GdObjectType GDCC_WORKER = new GdObjectType("GdccWorker");
    private static final GdObjectType GDCC_NODE = new GdObjectType("GdccNode");
    private static final GdObjectType UNKNOWN_OBJECT = new GdObjectType("UnknownObject");

    @Nested
    @DisplayName("Assignment and upcast baseline")
    class AssignmentBaseline {
        @Test
        @DisplayName("RefCounted assignment follows capture -> assign -> own -> release old")
        void refCountedAssignmentFollowsSlotWriteOrder() {
            var body = generateAssignmentBody(ENGINE_REFCOUNTED, ENGINE_REFCOUNTED, api());

            assertOrder(
                    body,
                    " = $dst;",
                    "$dst = $src;",
                    "own_object(gdcc_RefCounted_fat_ptr_live_object($dst));",
                    "release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_"
            );
            assertFalse(body.contains("try_own_object("), body);
            assertFalse(body.contains("try_release_object("), body);
        }

        @Test
        @DisplayName("non-RefCounted assignment captures old value but emits no retain/release")
        void nonRefCountedAssignmentEmitsNoLifecycleCalls() {
            var body = generateAssignmentBody(ENGINE_NODE, ENGINE_NODE, api());

            assertTrue(body.contains("__gdcc_tmp_old_obj_"), body);
            assertTrue(body.contains("$dst = $src;"), body);
            assertFalse(body.contains("own_object("), body);
            assertFalse(body.contains("try_own_object("), body);
            assertFalse(body.contains("release_object("), body);
            assertFalse(body.contains("try_release_object("), body);
        }

        @Test
        @DisplayName("unknown object assignment fails fast (no bare GDExtensionObjectPtr fallback)")
        void unknownObjectAssignmentFailsFast() {
            assertThrows(IllegalStateException.class, () -> generateAssignmentBody(UNKNOWN_OBJECT, UNKNOWN_OBJECT, api()));
        }

        @Test
        @DisplayName("GDCC child to engine parent assignment uses fat pointer upcast helper")
        void gdccToEngineAssignmentUsesFatPtrUpcast() {
            var workerClass = newClass("Worker");
            var gdccNodeClass = new LirClassDef("GdccNode", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
            var func = new LirFunctionDef("assign_upcast");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("dst", ENGINE_NODE);
            func.createAndAddVariable("src", GDCC_NODE);
            addEntry(func, new AssignInsn("dst", "src"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass, gdccNodeClass));

            assertTrue(body.contains("$dst = gdcc_GdccNode_fat_ptr_upcast_to_Node($src);"), body);
        }
    }

    @Nested
    @DisplayName("Move-return baseline")
    class MoveReturnBaseline {
        @Test
        @DisplayName("returning an owning local object moves it into _return_val and clears the source")
        void owningLocalReturnMovesAndClearsSource() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("move_return");
            func.setReturnType(ENGINE_NODE);
            func.createAndAddVariable("node", ENGINE_NODE);
            var builder = newBuilder(workerClass, func, api());

            builder.beginBasicBlock("entry");
            builder.returnValue(builder.valueOfVar(Objects.requireNonNull(func.getVariableById("node"))));
            var output = builder.build();

            assertTrue(output.contains("_return_val = $node;"), output);
            assertTrue(output.contains("$node = (gdcc_Node_fat_ptr){ 0 };"), output);
            assertFalse(output.contains("own_object("), output);
            assertFalse(output.contains("release_object("), output);
        }

        @Test
        @DisplayName("returning a parameter does not clear the source slot")
        void parameterReturnDoesNotClearSource() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("borrowed_return");
            func.setReturnType(ENGINE_NODE);
            func.addParameter(new LirParameterDef("node", ENGINE_NODE, null, func));
            var builder = newBuilder(workerClass, func, api());

            builder.beginBasicBlock("entry");
            builder.returnValue(builder.valueOfVar(Objects.requireNonNull(func.getVariableById("node"))));
            var output = builder.build();

            assertTrue(output.contains("_return_val = $node;"), output);
            assertFalse(output.contains("$node = (gdcc_Node_fat_ptr){ 0 };"), output);
        }
    }

    @Nested
    @DisplayName("Constructor and singleton baseline")
    class ConstructorSingletonBaseline {
        @Test
        @DisplayName("engine object construction captures raw constructor into fat pointer without extra retain")
        void engineConstructionCapturesIntoFatPointer() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("construct_node");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            addEntry(func, new ConstructObjectInsn("node", "Node"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_new_Node()))"), body);
            assertFalse(body.contains("own_object("), body);
            assertFalse(body.contains("try_own_object("), body);
        }

        @Test
        @DisplayName("GDCC RefCounted construction captures create_instance raw into fat pointer")
        void gdccRefCountedConstructionCapturesIntoFatPointer() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var func = new LirFunctionDef("construct_worker");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("worker", GDCC_WORKER);
            addEntry(func, new ConstructObjectInsn("worker", "GdccWorker"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass, gdccWorkerClass));

            assertTrue(body.contains("gdcc_ref_counted_init_raw(GdccWorker_class_create_instance(NULL, false), true)"), body);
            assertTrue(body.contains("gdcc_GdccWorker_fat_ptr_from_raw"), body);
            assertFalse(body.contains("own_object("), body);
            assertFalse(body.contains("try_own_object("), body);
        }

        @Test
        @DisplayName("singleton load captures raw engine singleton into fat pointer")
        void singletonLoadCapturesIntoFatPointer() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("load_singleton");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("out", ENGINE_NODE);
            addEntry(func, new LoadStaticInsn("out", "@GlobalScope", "GameSingleton"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, singletonApi(), List.of(workerClass));

            assertTrue(body.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_GameSingleton_singleton()))"), body);
        }
    }

    @Nested
    @DisplayName("Variant pack/unpack baseline")
    class VariantPackUnpackBaseline {
        @Test
        @DisplayName("packing object values uses per-type fat pointer to_variant helpers")
        void packUsesFatPointerToVariantHelpers() {
            var engineBody = generatePackBody(ENGINE_NODE, api());
            assertTrue(engineBody.contains("gdcc_Node_fat_ptr_to_variant($value)"), engineBody);

            var gdccBody = generatePackBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("gdcc_GdccWorker_fat_ptr_to_variant($value)"), gdccBody);
        }

        @Test
        @DisplayName("unpacking object values is BORROWED from_variant and releases old via live_object")
        void unpackUsesFromVariantBorrowedAndReleasesOld() {
            var engineBody = generateUnpackBody(ENGINE_REFCOUNTED, api());
            assertTrue(engineBody.contains("gdcc_RefCounted_fat_ptr_from_variant"), engineBody);
            assertTrue(engineBody.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_"), engineBody);
            assertFalse(engineBody.contains("own_object($result)"), engineBody);

            var gdccBody = generateUnpackBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("gdcc_GdccWorker_fat_ptr_from_variant"), gdccBody);
            assertTrue(gdccBody.contains("release_object(gdcc_GdccWorker_fat_ptr_live_object(__gdcc_tmp_old_obj_"), gdccBody);
            assertFalse(gdccBody.contains("own_object($result)"), gdccBody);

            assertThrows(IllegalStateException.class, () -> generateUnpackBody(UNKNOWN_OBJECT, api()));
        }
    }

    @Nested
    @DisplayName("Equality and null comparison normalized-raw anchor")
    class EqualityBaseline {
        @Test
        @DisplayName("engine object equality uses C1 normalized raw (null∪freed→NULL, live→.ptr)")
        void objectEqualityUsesNormalizedRawComparison() {
            var equalBody = generateBinaryOpBody(GodotOperator.EQUAL);
            assertTrue(equalBody.contains(
                            "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left).ptr, $left.instance_id) ? NULL : (GDExtensionObjectPtr)($left).ptr)"),
                    equalBody);
            assertTrue(equalBody.contains(
                            "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right).ptr, $right.instance_id) ? NULL : (GDExtensionObjectPtr)($right).ptr)"),
                    equalBody);
            assertTrue(equalBody.contains(" == "), equalBody);
            assertFalse(equalBody.contains(".instance_id =="), equalBody);
            assertFalse(equalBody.contains("godot_object_get_instance_id("), equalBody);

            var notEqualBody = generateBinaryOpBody(GodotOperator.NOT_EQUAL);
            assertTrue(notEqualBody.contains(" != "), notEqualBody);
            assertFalse(notEqualBody.contains(".instance_id !="), notEqualBody);
        }

        @Test
        @DisplayName("GDCC object equality uses live_object after nullness gate, never dead object_ptr")
        void gdccObjectEqualityUsesNormalizedLiveObject() {
            var workerClass = newClass("Worker");
            var gdccWorker = newClass("GdccWorker");
            var func = new LirFunctionDef("gdcc_object_equal");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("left", GDCC_WORKER);
            func.createAndAddVariable("right", GDCC_WORKER);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.EQUAL, "left", "right"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass, gdccWorker));

            assertTrue(body.contains(
                            "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left).ptr, $left.instance_id) ? NULL : gdcc_GdccWorker_fat_ptr_live_object($left))"),
                    body);
            assertTrue(body.contains(
                            "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right).ptr, $right.instance_id) ? NULL : gdcc_GdccWorker_fat_ptr_live_object($right))"),
                    body);
            assertFalse(body.contains("GdccWorker_object_ptr"), body);
            assertFalse(body.contains(".instance_id =="), body);
            assertFalse(body.contains("godot_object_get_instance_id("), body);
        }

        @Test
        @DisplayName("object-vs-nil equality uses gdcc_object_is_null_raw_and_id")
        void objectNilEqualityUsesRawAndId() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("object_nil_equal");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("obj", ENGINE_NODE);
            func.createAndAddVariable("nil", GdNilType.NIL);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.EQUAL, "obj", "nil"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
        }

        @Test
        @DisplayName("object-vs-nil inequality negates gdcc_object_is_null_raw_and_id")
        void objectNilInequalityUsesRawAndId() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("object_nil_not_equal");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("obj", ENGINE_NODE);
            func.createAndAddVariable("nil", GdNilType.NIL);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "obj", "nil"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("(!gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id))"), body);
        }

        @Test
        @DisplayName("object ordering comparisons fail fast")
        void objectOrderingComparisonFailsFast() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("object_greater");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("left", ENGINE_NODE);
            func.createAndAddVariable("right", ENGINE_NODE);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"));
            workerClass.addFunction(func);

            var ex = assertThrows(
                    InvalidInsnException.class,
                    () -> generateFuncBody(workerClass, func, api(), List.of(workerClass))
            );
            assertTrue(ex.getMessage().contains("Object comparison supports only == and !="), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("RefCounted status matrix baseline")
    class RefCountedMatrixBaseline {
        @Test
        @DisplayName("explicit own/release selects plain, try_*, or no-op helpers via live_object")
        void explicitOwnReleaseFollowsRefCountedStatus() {
            var gdccBody = generateOwnReleaseBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("own_object(gdcc_GdccWorker_fat_ptr_live_object($obj));"), gdccBody);
            assertTrue(gdccBody.contains("release_object(gdcc_GdccWorker_fat_ptr_live_object($obj));"), gdccBody);

            assertThrows(IllegalStateException.class, () -> generateOwnReleaseBody(UNKNOWN_OBJECT, api()));

            var objectBody = generateOwnReleaseBody(ENGINE_OBJECT, api());
            assertTrue(objectBody.contains("try_own_object(gdcc_Object_fat_ptr_live_object($obj), $obj.instance_id);"), objectBody);
            assertTrue(objectBody.contains("try_release_object(gdcc_Object_fat_ptr_live_object($obj), $obj.instance_id);"), objectBody);
            assertFalse(objectBody.contains("\nown_object(gdcc_Object_fat_ptr_live_object($obj));"), objectBody);
            assertFalse(objectBody.contains("\nrelease_object(gdcc_Object_fat_ptr_live_object($obj));"), objectBody);

            var nodeBody = generateOwnReleaseBody(ENGINE_NODE, api());
            assertFalse(nodeBody.contains("own_object("), nodeBody);
            assertFalse(nodeBody.contains("try_own_object("), nodeBody);
            assertFalse(nodeBody.contains("release_object("), nodeBody);
            assertFalse(nodeBody.contains("try_release_object("), nodeBody);
        }

        @Test
        @DisplayName("__finally__ auto cleanup releases RefCounted/Object slots via live_object but skips non-RefCounted")
        void autoFinallyCleanupFollowsRefCountedStatus() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var func = new LirFunctionDef("auto_cleanup");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            func.createAndAddVariable("obj", ENGINE_OBJECT);
            func.createAndAddVariable("ref", ENGINE_REFCOUNTED);
            func.createAndAddVariable("worker", GDCC_WORKER);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var entrySource = generateEntryC(api(), List.of(workerClass, gdccWorkerClass));

            assertTrue(entrySource.contains("release_object(gdcc_RefCounted_fat_ptr_live_object($ref));"), entrySource);
            assertTrue(entrySource.contains("release_object(gdcc_GdccWorker_fat_ptr_live_object($worker));"), entrySource);
            assertTrue(entrySource.contains("try_release_object(gdcc_Object_fat_ptr_live_object($obj), $obj.instance_id);"), entrySource);
            assertFalse(entrySource.contains("try_destroy_object($node);"), entrySource);
            assertFalse(entrySource.contains("destroy_object($node);"), entrySource);
            assertFalse(entrySource.contains("release_object(gdcc_Node_fat_ptr_live_object($node));"), entrySource);
        }

        @Test
        @DisplayName("Object assignment uses try_own / try_release for possible RC instances")
        void objectAssignmentUsesTryLifecycleCalls() {
            var body = generateAssignmentBody(ENGINE_OBJECT, ENGINE_OBJECT, api());

            assertOrder(
                    body,
                    " = $dst;",
                    "$dst = $src;",
                    "try_own_object(gdcc_Object_fat_ptr_live_object($dst), $dst.instance_id);",
                    "try_release_object(gdcc_Object_fat_ptr_live_object(__gdcc_tmp_old_obj_0), __gdcc_tmp_old_obj_0.instance_id);"
            );
            assertFalse(body.contains("\nown_object(gdcc_Object_fat_ptr_live_object($dst));"), body);
            assertFalse(body.contains("\nrelease_object(gdcc_Object_fat_ptr_live_object(__gdcc_tmp_old_obj_"), body);
        }
    }

    private static String generateAssignmentBody(GdObjectType dstType, GdObjectType srcType, ExtensionAPI api) {
        var workerClass = newClass("Worker");
        var gdccClasses = new ArrayList<>(List.of(workerClass));
        if (srcType.equals(GDCC_NODE)) {
            gdccClasses.add(new LirClassDef("GdccNode", "Node", false, false, Map.of(), List.of(), List.of(), List.of()));
        }
        var func = new LirFunctionDef("assign_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", dstType);
        func.createAndAddVariable("src", srcType);
        addEntry(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);
        return generateFuncBody(workerClass, func, api, gdccClasses);
    }

    private static String generatePackBody(GdObjectType objectType, ExtensionAPI api) {
        var workerClass = newClass("Worker");
        var gdccClasses = new ArrayList<>(List.of(workerClass));
        if (objectType.equals(GDCC_WORKER)) {
            gdccClasses.add(newClass("GdccWorker"));
        }
        var func = new LirFunctionDef("pack_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdVariantType.VARIANT);
        func.createAndAddVariable("value", objectType);
        addEntry(func, new PackVariantInsn("result", "value"));
        workerClass.addFunction(func);
        return generateFuncBody(workerClass, func, api, gdccClasses);
    }

    private static String generateUnpackBody(GdObjectType objectType, ExtensionAPI api) {
        var workerClass = newClass("Worker");
        var gdccClasses = new ArrayList<>(List.of(workerClass));
        if (objectType.equals(GDCC_WORKER)) {
            gdccClasses.add(newClass("GdccWorker"));
        }
        var func = new LirFunctionDef("unpack_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", objectType);
        func.createAndAddVariable("variant", GdVariantType.VARIANT);
        addEntry(func, new UnpackVariantInsn("result", "variant"));
        workerClass.addFunction(func);
        return generateFuncBody(workerClass, func, api, gdccClasses);
    }

    private static String generateBinaryOpBody(GodotOperator operator) {
        var workerClass = newClass("Worker");
        var func = new LirFunctionDef("object_equal");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", ENGINE_NODE);
        func.createAndAddVariable("right", ENGINE_NODE);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        addEntry(func, new BinaryOpInsn("result", operator, "left", "right"));
        workerClass.addFunction(func);
        return generateFuncBody(workerClass, func, api(), List.of(workerClass));
    }

    private static String generateOwnReleaseBody(GdObjectType objectType, ExtensionAPI api) {
        var workerClass = newClass("Worker");
        var gdccClasses = new ArrayList<>(List.of(workerClass));
        if (objectType.equals(GDCC_WORKER)) {
            gdccClasses.add(newClass("GdccWorker"));
        }
        var func = new LirFunctionDef("own_release");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", objectType);
        addEntry(func,
                new TryOwnObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT),
                new TryReleaseObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT)
        );
        workerClass.addFunction(func);
        return generateFuncBody(workerClass, func, api, gdccClasses);
    }

    private static String generateFuncBody(LirClassDef mainClass, LirFunctionDef func, ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var module = new LirModule("object_lifecycle_module", gdccClasses);
        var codegen = newCodegen(module, api, gdccClasses);
        return codegen.generateFuncBody(mainClass, func);
    }

    private static String generateEntryC(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var module = new LirModule("object_lifecycle_module", gdccClasses);
        var codegen = newCodegen(module, api, gdccClasses);
        return codegen.generate().stream()
                .filter(file -> file.filePath().equals("entry.c"))
                .findFirst()
                .map(GeneratedFile::contentWriter)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow();
    }

    private static CBodyBuilder newBuilder(LirClassDef classDef, LirFunctionDef func, ExtensionAPI api) {
        var classRegistry = new ClassRegistry(api);
        classRegistry.addGdccClass(classDef);
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var helper = new CGenHelper(new CodegenContext(projectInfo, classRegistry), List.of(classDef));
        return new CBodyBuilder(helper, classDef, func);
    }

    private static CCodegen newCodegen(LirModule module, ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        return codegen;
    }

    private static LirClassDef newClass(String name) {
        return new LirClassDef(name, "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private static void addEntry(LirFunctionDef func, LirInstruction... instructions) {
        var entry = new LirBasicBlock("entry");
        for (var instruction : instructions) {
            entry.appendInstruction(instruction);
        }
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private static void addEntryReturn(LirFunctionDef func) {
        addEntry(func);
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertOrder(String body, String... fragments) {
        var lastIndex = -1;
        for (var fragment : fragments) {
            var index = body.indexOf(fragment);
            assertTrue(index >= 0, "missing fragment: " + fragment + "\n" + body);
            assertTrue(index > lastIndex, "fragment out of order: " + fragment + "\n" + body);
            lastIndex = index;
        }
    }

    private static ExtensionAPI api() {
        var object = new ExtensionGdClass(
                "Object", false, true, "", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var refCounted = new ExtensionGdClass(
                "RefCounted", true, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var node = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        return new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(object, refCounted, node), List.of(), List.of()
        );
    }

    private static ExtensionAPI singletonApi() {
        var object = new ExtensionGdClass(
                "Object", false, true, "", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var node = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        return new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(object, node), List.of(new ExtensionSingleton("GameSingleton", "Node")), List.of()
        );
    }
}
