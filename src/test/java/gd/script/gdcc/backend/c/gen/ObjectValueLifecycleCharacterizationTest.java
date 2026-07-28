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

/// Phase 0 characterization for current object lifecycle, conversion, comparison and Variant boundary
/// behavior. These tests anchor the bare-pointer ownership baseline that the fat-pointer migration
/// must preserve or deliberately change.
class ObjectValueLifecycleCharacterizationTest {
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
                    "own_object($dst);",
                    "release_object(__gdcc_tmp_old_obj_"
            );
            assertFalse(body.contains("try_own_object($dst);"), body);
            assertFalse(body.contains("try_release_object(__gdcc_tmp_old_obj_"), body);
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
        @DisplayName("unknown object assignment uses runtime try_own/try_release helpers")
        void unknownObjectAssignmentUsesTryHelpers() {
            var body = generateAssignmentBody(UNKNOWN_OBJECT, UNKNOWN_OBJECT, api());

            assertTrue(body.contains("try_own_object($dst);"), body);
            assertTrue(body.contains("try_release_object(__gdcc_tmp_old_obj_"), body);
            assertFalse(body.contains("\nown_object($dst);\n"), body);
            assertFalse(body.contains("\nrelease_object(__gdcc_tmp_old_obj_"), body);
        }

        @Test
        @DisplayName("GDCC child to engine parent assignment converts wrapper pointer to raw Godot pointer")
        void gdccToEngineAssignmentConvertsPointerRepresentation() {
            var workerClass = newClass("Worker");
            var gdccNodeClass = new LirClassDef("GdccNode", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
            var func = new LirFunctionDef("assign_upcast");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("dst", ENGINE_NODE);
            func.createAndAddVariable("src", GDCC_NODE);
            addEntry(func, new AssignInsn("dst", "src"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass, gdccNodeClass));

            assertTrue(body.contains("$dst = gdcc_object_to_godot_object_ptr($src, GdccNode_object_ptr);"), body);
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
            assertTrue(output.contains("$node = NULL;"), output);
            assertFalse(output.contains("own_object("), output);
            assertFalse(output.contains("release_object($node)"), output);
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
            assertFalse(output.contains("$node = NULL;"), output);
        }
    }

    @Nested
    @DisplayName("Constructor and singleton baseline")
    class ConstructorSingletonBaseline {
        @Test
        @DisplayName("engine object construction produces a fresh bare pointer without extra retain")
        void engineConstructionProducesBarePointer() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("construct_node");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            addEntry(func, new ConstructObjectInsn("node", "Node"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("$node = godot_new_Node();"), body);
            assertFalse(body.contains("own_object($node);"), body);
            assertFalse(body.contains("try_own_object($node);"), body);
        }

        @Test
        @DisplayName("GDCC RefCounted construction wraps the raw instance through gdcc_object_from_godot_object_ptr")
        void gdccRefCountedConstructionWrapsRawInstance() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var func = new LirFunctionDef("construct_worker");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("worker", GDCC_WORKER);
            addEntry(func, new ConstructObjectInsn("worker", "GdccWorker"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass, gdccWorkerClass));

            assertTrue(body.contains("gdcc_ref_counted_init_raw(GdccWorker_class_create_instance(NULL, false), true)"), body);
            assertTrue(body.contains("gdcc_object_from_godot_object_ptr("), body);
            assertFalse(body.contains("own_object("), body);
            assertFalse(body.contains("try_own_object("), body);
        }

        @Test
        @DisplayName("singleton load produces the raw engine singleton pointer")
        void singletonLoadProducesBarePointer() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("load_singleton");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("out", ENGINE_NODE);
            addEntry(func, new LoadStaticInsn("out", "@GlobalScope", "GameSingleton"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, singletonApi(), List.of(workerClass));

            assertTrue(body.contains("$out = godot_GameSingleton_singleton();"), body);
        }
    }

    @Nested
    @DisplayName("Variant pack/unpack baseline")
    class VariantPackUnpackBaseline {
        @Test
        @DisplayName("packing object values uses the current bare-pointer Variant constructors")
        void packUsesBarePointerVariantConstructors() {
            var engineBody = generatePackBody(ENGINE_NODE, api());
            assertTrue(engineBody.contains("$result = godot_new_Variant_with_Object($value);"), engineBody);

            var gdccBody = generatePackBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("$result = gdcc_new_Variant_with_gdcc_Object($value);"), gdccBody);
        }

        @Test
        @DisplayName("unpacking object values releases the old slot but does not re-own the fresh helper result")
        void unpackReleasesOldWithoutReowning() {
            var engineBody = generateUnpackBody(ENGINE_REFCOUNTED, api());
            assertTrue(engineBody.contains("$result = (godot_RefCounted*)godot_new_Object_with_Variant(&$variant);"), engineBody);
            assertTrue(engineBody.contains("release_object(__gdcc_tmp_old_obj_"), engineBody);
            assertFalse(engineBody.contains("own_object($result)"), engineBody);

            var gdccBody = generateUnpackBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("$result = (GdccWorker*)godot_new_gdcc_Object_with_Variant(&$variant);"), gdccBody);
            assertTrue(gdccBody.contains("release_object(gdcc_object_to_godot_object_ptr(__gdcc_tmp_old_obj_"), gdccBody);
            assertFalse(gdccBody.contains("own_object($result)"), gdccBody);

            var unknownBody = generateUnpackBody(UNKNOWN_OBJECT, api());
            assertTrue(unknownBody.contains("$result = (godot_UnknownObject*)godot_new_Object_with_Variant(&$variant);"), unknownBody);
            assertTrue(unknownBody.contains("try_release_object(__gdcc_tmp_old_obj_"), unknownBody);
            assertFalse(unknownBody.contains("own_object($result)"), unknownBody);
        }
    }

    @Nested
    @DisplayName("Equality and null comparison phase-2 anchor")
    class EqualityBaseline {
        @Test
        @DisplayName("object equality compares raw Godot object pointers directly")
        void objectEqualityUsesRawPointerComparison() {
            var equalBody = generateBinaryOpBody(GodotOperator.EQUAL);
            assertTrue(equalBody.contains("$result = ($left == $right);"), equalBody);

            var notEqualBody = generateBinaryOpBody(GodotOperator.NOT_EQUAL);
            assertTrue(notEqualBody.contains("$result = ($left != $right);"), notEqualBody);
        }

        @Test
        @DisplayName("object-vs-nil equality compares the raw object pointer against NULL")
        void objectNilEqualityUsesRawNullCompare() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("object_nil_equal");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("obj", ENGINE_NODE);
            func.createAndAddVariable("nil", GdNilType.NIL);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.EQUAL, "obj", "nil"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("$result = ($obj == NULL);"), body);
        }

        @Test
        @DisplayName("object-vs-nil inequality compares the raw object pointer against NULL")
        void objectNilInequalityUsesRawNullCompare() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("object_nil_not_equal");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("obj", ENGINE_NODE);
            func.createAndAddVariable("nil", GdNilType.NIL);
            func.createAndAddVariable("result", GdBoolType.BOOL);
            addEntry(func, new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "obj", "nil"));
            workerClass.addFunction(func);

            var body = generateFuncBody(workerClass, func, api(), List.of(workerClass));

            assertTrue(body.contains("$result = ($obj != NULL);"), body);
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
        @DisplayName("explicit own/release selects plain, try, or no-op helpers by RefCounted status")
        void explicitOwnReleaseFollowsRefCountedStatus() {
            var gdccBody = generateOwnReleaseBody(GDCC_WORKER, api());
            assertTrue(gdccBody.contains("own_object(gdcc_object_to_godot_object_ptr($obj, GdccWorker_object_ptr));"), gdccBody);
            assertTrue(gdccBody.contains("release_object(gdcc_object_to_godot_object_ptr($obj, GdccWorker_object_ptr));"), gdccBody);

            var unknownBody = generateOwnReleaseBody(UNKNOWN_OBJECT, api());
            assertTrue(unknownBody.contains("try_own_object($obj);"), unknownBody);
            assertTrue(unknownBody.contains("try_release_object($obj);"), unknownBody);

            var nodeBody = generateOwnReleaseBody(ENGINE_NODE, api());
            assertFalse(nodeBody.contains("own_object("), nodeBody);
            assertFalse(nodeBody.contains("try_own_object("), nodeBody);
            assertFalse(nodeBody.contains("release_object("), nodeBody);
            assertFalse(nodeBody.contains("try_release_object("), nodeBody);
        }

        @Test
        @DisplayName("__finally__ auto cleanup releases RefCounted slots but skips definite non-RefCounted slots")
        void autoFinallyCleanupFollowsRefCountedStatus() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var func = new LirFunctionDef("auto_cleanup");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            func.createAndAddVariable("ref", ENGINE_REFCOUNTED);
            func.createAndAddVariable("worker", GDCC_WORKER);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var entrySource = generateEntryC(api(), List.of(workerClass, gdccWorkerClass));

            assertTrue(entrySource.contains("release_object($ref);"), entrySource);
            assertTrue(entrySource.contains("release_object(gdcc_object_to_godot_object_ptr($worker, GdccWorker_object_ptr));"), entrySource);
            assertFalse(entrySource.contains("try_destroy_object($node);"), entrySource);
            assertFalse(entrySource.contains("destroy_object($node);"), entrySource);
            assertFalse(entrySource.contains("release_object($node);"), entrySource);
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
        var module = new LirModule("phase0_lifecycle_module", gdccClasses);
        var codegen = newCodegen(module, api, gdccClasses);
        return codegen.generateFuncBody(mainClass, func);
    }

    private static String generateEntryC(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var module = new LirModule("phase0_lifecycle_module", gdccClasses);
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
                List.of(refCounted, node), List.of(), List.of()
        );
    }

    private static ExtensionAPI singletonApi() {
        var node = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        return new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(node), List.of(new ExtensionSingleton("GameSingleton", "Node")), List.of()
        );
    }
}
