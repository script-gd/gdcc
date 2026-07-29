package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Characterization for object value representation under the fat-pointer C contract.
///
/// Positive paths freeze internal storage/parameter/return as `gdcc_<Type>_fat_ptr` and keep raw
/// pointer spellings only on ABI/layout edges (bare/raw renderer, container elements, receivers).
/// Negative paths fail fast on unknown object types instead of falling back to `GDExtensionObjectPtr`.
class ObjectValueRepresentationCharacterizationTest {
    private static final GdObjectType ENGINE_OBJECT = new GdObjectType("Object");
    private static final GdObjectType ENGINE_NODE = new GdObjectType("Node");
    private static final GdObjectType ENGINE_REFCOUNTED = new GdObjectType("RefCounted");
    private static final GdObjectType GDCC_WORKER = new GdObjectType("GdccWorker");
    private static final GdObjectType UNKNOWN_OBJECT = new GdObjectType("UnknownObject");

    @Nested
    @DisplayName("Type rendering baseline")
    class TypeRenderingBaseline {
        @Test
        @DisplayName("engine object storage and parameter types are per-type fat pointers")
        void engineObjectRendersAsFatPointer() {
            var helper = newHelper();

            assertEquals("gdcc_Node_fat_ptr", helper.renderGdTypeInC(ENGINE_NODE));
            assertEquals("gdcc_Node_fat_ptr", helper.renderGdTypeRefInC(ENGINE_NODE));
            assertEquals("gdcc_RefCounted_fat_ptr", helper.renderGdTypeInC(ENGINE_REFCOUNTED));
            assertEquals("gdcc_RefCounted_fat_ptr", helper.renderGdTypeRefInC(ENGINE_REFCOUNTED));
            assertEquals("godot_Node*", helper.renderObjectBarePointerType(ENGINE_NODE));
            assertEquals("godot_Node *", helper.renderObjectRawPointerType(ENGINE_NODE));
        }

        @Test
        @DisplayName("GDCC object storage and parameter types are per-type fat pointers")
        void gdccObjectRendersAsFatPointer() {
            var helper = newHelper();

            assertEquals("gdcc_GdccWorker_fat_ptr", helper.renderGdTypeInC(GDCC_WORKER));
            assertEquals("gdcc_GdccWorker_fat_ptr", helper.renderGdTypeRefInC(GDCC_WORKER));
            assertEquals("GdccWorker*", helper.renderObjectBarePointerType(GDCC_WORKER));
        }

        @Test
        @DisplayName("unknown object types fail fast instead of falling back to GDExtensionObjectPtr")
        void unknownObjectFailsFast() {
            var helper = newHelper();

            assertThrows(IllegalStateException.class, () -> helper.renderGdTypeInC(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderGdTypeRefInC(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderDefaultValueExprInC(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectRawPointerType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectReceiverType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectBarePointerType(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("raw ABI boundary renderers stay raw while internal storage stays fat")
        void rawAbiBoundariesStayRawWhileStorageIsFat() {
            var helper = newHelper();

            assertEquals("gdcc_Node_fat_ptr", helper.renderGdTypeInC(ENGINE_NODE));
            assertEquals("godot_Node *", helper.renderObjectRawPointerType(ENGINE_NODE));
            assertEquals("GDExtensionObjectPtr", helper.renderObjectReceiverType(ENGINE_NODE));
            assertEquals("gdcc_GdccWorker_fat_ptr", helper.renderGdTypeInC(GDCC_WORKER));
            assertEquals("GdccWorker *", helper.renderObjectRawPointerType(GDCC_WORKER));
            assertEquals("GDExtensionObjectPtr", helper.renderObjectReceiverType(GDCC_WORKER));
        }

        @Test
        @DisplayName("object fat pointers are passed by value without address-of")
        void objectValueRefDoesNotAddAddressOf() {
            var helper = newHelper();

            assertEquals("$obj", helper.renderValueRef(ENGINE_NODE, "$obj"));
            assertEquals("$obj", helper.renderValueRef(GDCC_WORKER, "$obj"));
        }

        @Test
        @DisplayName("object default values are zeroed fat pointer compound literals")
        void objectDefaultValueIsZeroedFatPointer() {
            var helper = newHelper();

            assertEquals("(gdcc_Node_fat_ptr){ 0 }", helper.renderDefaultValueExprInC(ENGINE_NODE));
            assertEquals("(gdcc_GdccWorker_fat_ptr){ 0 }", helper.renderDefaultValueExprInC(GDCC_WORKER));
            assertThrows(IllegalArgumentException.class, () -> CBodyBuilder.renderDefaultValueExpr(ENGINE_NODE));
        }

        @Test
        @DisplayName("object pack/unpack helpers use per-type fat pointer conversion helpers")
        void objectPackUnpackUseFatPointerHelpers() {
            var helper = newHelper();

            assertEquals("gdcc_Node_fat_ptr_to_variant", helper.renderPackFunctionName(ENGINE_NODE));
            assertEquals("gdcc_GdccWorker_fat_ptr_to_variant", helper.renderPackFunctionName(GDCC_WORKER));
            assertEquals("gdcc_Node_fat_ptr_from_variant", helper.renderUnpackFunctionName(ENGINE_NODE));
            assertEquals("gdcc_GdccWorker_fat_ptr_from_variant", helper.renderUnpackFunctionName(GDCC_WORKER));
            assertThrows(IllegalStateException.class, () -> helper.renderPackFunctionName(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderUnpackFunctionName(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("Variant pack/unpack helper rendering rejects compiler-only and Nil types")
        void packUnpackHelpersRejectInvalidTypes() {
            var helper = newHelper();

            assertThrows(IllegalArgumentException.class, () -> helper.renderPackFunctionName(GdccForRangeIterType.FOR_RANGE_ITER));
            assertThrows(IllegalArgumentException.class, () -> helper.renderPackFunctionName(GdNilType.NIL));
            assertThrows(IllegalArgumentException.class, () -> helper.renderUnpackFunctionName(GdccForRangeIterType.FOR_RANGE_ITER));
        }

        @Test
        @DisplayName("object destroy uses the shared raw object destroy helper and copy assignment is direct")
        void objectDestroyAndCopyAssignContracts() {
            var helper = newHelper();

            assertEquals("godot_object_destroy", helper.renderDestroyFunctionName(ENGINE_NODE));
            assertEquals("godot_object_destroy", helper.renderDestroyFunctionName(GDCC_WORKER));
            assertEquals("", helper.renderCopyAssignFunctionName(ENGINE_NODE));
            assertEquals("", helper.renderCopyAssignFunctionName(GDCC_WORKER));
        }

        @Test
        @DisplayName("call wrapper cleanup skips object locals but destroys non-object wrapper locals")
        void callWrapperCleanupSkipsObjects() {
            var helper = newHelper();

            assertEquals("", helper.renderCallWrapperDestroyStmt(ENGINE_NODE, "arg0"));
            assertEquals("", helper.renderCallWrapperDestroyStmt(GDCC_WORKER, "arg0"));
            assertEquals("godot_String_destroy(&arg0);", helper.renderCallWrapperDestroyStmt(GdStringType.STRING, "arg0"));
        }

        @Test
        @DisplayName("call wrapper consumes OWNED object returns after Variant pack")
        void callWrapperConsumesOwnedObjectReturns() {
            var helper = newHelper();

            assertEquals("", helper.renderCallWrapperOwnedObjectReturnConsumeStmt(ENGINE_NODE, "r"));
            assertEquals(
                    "try_release_object(gdcc_Object_fat_ptr_live_object(r), r.instance_id);",
                    helper.renderCallWrapperOwnedObjectReturnConsumeStmt(ENGINE_OBJECT, "r")
            );
            assertEquals(
                    "release_object(gdcc_GdccWorker_fat_ptr_live_object(r));",
                    helper.renderCallWrapperOwnedObjectReturnConsumeStmt(GDCC_WORKER, "r")
            );
            assertEquals(
                    "release_object(gdcc_RefCounted_fat_ptr_live_object(r));",
                    helper.renderCallWrapperOwnedObjectReturnConsumeStmt(ENGINE_REFCOUNTED, "r")
            );
        }
    }

    @Nested
    @DisplayName("Function surface baseline")
    class FunctionSurfaceBaseline {
        @Test
        @DisplayName("object locals are declared with fat pointer storage types")
        void objectLocalUsesFatPointerStorage() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("use_object_local");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            func.createAndAddVariable("worker", GDCC_WORKER);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var entrySource = generateFile(List.of(workerClass, newClass("GdccWorker")), "entry.c");

            assertTrue(entrySource.contains("gdcc_Node_fat_ptr $node;"), entrySource);
            assertTrue(entrySource.contains("gdcc_GdccWorker_fat_ptr $worker;"), entrySource);
            assertFalse(entrySource.contains("godot_Node* $node"), entrySource);
            assertFalse(entrySource.contains("GdccWorker* $worker"), entrySource);
        }

        @Test
        @DisplayName("object return slots are declared as fat pointers zero-initialized")
        void objectReturnSlotIsZeroedFatPointer() {
            assertEquals(
                    "__prepare__: // __prepare__\ngdcc_Node_fat_ptr _return_val = (gdcc_Node_fat_ptr){ 0 };\n",
                    prepareBlockOutputForReturn(ENGINE_NODE)
            );
            assertEquals(
                    "__prepare__: // __prepare__\ngdcc_GdccWorker_fat_ptr _return_val = (gdcc_GdccWorker_fat_ptr){ 0 };\n",
                    prepareBlockOutputForReturn(GDCC_WORKER)
            );
            assertThrows(IllegalStateException.class, () -> prepareBlockOutputForReturn(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("internal function headers use fat pointer parameter and return types")
        void internalFunctionHeadersUseFatPointers() {
            var workerClass = newClass("Worker");

            var takeEngine = new LirFunctionDef("take_engine");
            takeEngine.setReturnType(GdVoidType.VOID);
            takeEngine.addParameter(new LirParameterDef("value", ENGINE_NODE, null, takeEngine));
            addEntryReturn(takeEngine);
            workerClass.addFunction(takeEngine);

            addObjectReturnFunction(workerClass, "return_engine", ENGINE_NODE);

            var entryHeader = generateEntryHeader(List.of(workerClass, newClass("GdccWorker")));

            assertTrue(entryHeader.contains("void Worker_take_engine("), entryHeader);
            assertTrue(entryHeader.contains("gdcc_Node_fat_ptr $value"), entryHeader);
            assertTrue(entryHeader.contains("gdcc_Node_fat_ptr Worker_return_engine("), entryHeader);
        }
    }

    @Nested
    @DisplayName("Registered wrapper baseline")
    class RegisteredWrapperBaseline {
        @Test
        @DisplayName("call_func unpacks object arguments into fat pointers via from_variant")
        void callFuncUnpacksObjectArgumentsIntoFatPointers() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertTrue(entryHeader.contains(
                    "const gdcc_Node_fat_ptr arg0 = gdcc_Node_fat_ptr_from_variant((GDExtensionVariantPtr)p_args[0]);"
            ), entryHeader);
            assertTrue(entryHeader.contains(
                    "const gdcc_GdccWorker_fat_ptr arg0 = gdcc_GdccWorker_fat_ptr_from_variant((GDExtensionVariantPtr)p_args[0]);"
            ), entryHeader);
        }

        @Test
        @DisplayName("call_func packs object returns through fat pointer to_variant helpers")
        void callFuncPacksObjectReturnsFromFatPointers() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertTrue(entryHeader.contains("gdcc_Node_fat_ptr r = function(self_fat);"), entryHeader);
            assertTrue(entryHeader.contains("godot_Variant ret = gdcc_Node_fat_ptr_to_variant(r);"), entryHeader);
            assertTrue(entryHeader.contains("gdcc_GdccWorker_fat_ptr r = function(self_fat);"), entryHeader);
            assertTrue(entryHeader.contains("godot_Variant ret = gdcc_GdccWorker_fat_ptr_to_variant(r);"), entryHeader);
            // Object return: packing establishes Variant ownership but does not consume the function's
            // OWNED strong reference on r. RefCounted returns must release r after destroy(ret);
            // non-RefCounted (Node) must not.
            assertTrue(entryHeader.contains("release_object(gdcc_GdccWorker_fat_ptr_live_object(r));"), entryHeader);
            assertFalse(entryHeader.contains("release_object(gdcc_Node_fat_ptr_live_object(r));"), entryHeader);
            var packIdx = entryHeader.indexOf("godot_Variant ret = gdcc_GdccWorker_fat_ptr_to_variant(r);");
            var destroyIdx = entryHeader.indexOf("godot_Variant_destroy(&ret);", packIdx);
            var releaseIdx = entryHeader.indexOf("release_object(gdcc_GdccWorker_fat_ptr_live_object(r));", destroyIdx);
            assertTrue(packIdx >= 0 && destroyIdx > packIdx && releaseIdx > destroyIdx,
                    "pack → destroy temp Variant → release OWNED r order required.\n" + entryHeader);
        }

        @Test
        @DisplayName("same ABI shape on different owners generates distinct instance wrappers")
        void sameAbiShapeDifferentOwnersGetDistinctWrappers() {
            var ownerA = newClass("OwnerA");
            var ownerB = newClass("OwnerB");
            for (var owner : List.of(ownerA, ownerB)) {
                var take = new LirFunctionDef("take_node");
                take.setReturnType(GdVoidType.VOID);
                take.addParameter(new LirParameterDef("self", new GdObjectType(owner.getName()), null, take));
                take.addParameter(new LirParameterDef("value", ENGINE_NODE, null, take));
                addEntryReturn(take);
                owner.addFunction(take);
            }
            var entryHeader = generateEntryHeader(List.of(ownerA, ownerB));
            assertTrue(entryHeader.contains("call_OwnerA_"), entryHeader);
            assertTrue(entryHeader.contains("call_OwnerB_"), entryHeader);
            assertTrue(entryHeader.contains("ptrcall_OwnerA_"), entryHeader);
            assertTrue(entryHeader.contains("ptrcall_OwnerB_"), entryHeader);
            assertTrue(entryHeader.contains("gdcc_OwnerA_fat_ptr_from_raw(OwnerA_object_ptr((OwnerA*)p_instance))"), entryHeader);
            assertTrue(entryHeader.contains("gdcc_OwnerB_fat_ptr_from_raw(OwnerB_object_ptr((OwnerB*)p_instance))"), entryHeader);
            assertFalse(entryHeader.contains("call_1_arg_Node_no_ret("), entryHeader);
        }

        @Test
        @DisplayName("ptrcall uses raw Godot object slots and owner fat self")
        void ptrcallUsesRawGodotSlotsAndOwnerFatSelf() {
            var entryHeader = generateRegisteredWrapperHeader();

            // Object args: raw pointer slot -> fat via from_raw (not fat struct at p_args).
            assertTrue(entryHeader.contains("gdcc_Node_fat_ptr arg0 = gdcc_Node_fat_ptr_from_raw(*((const GDExtensionObjectPtr *)p_args[0]));"), entryHeader);
            assertTrue(entryHeader.contains("gdcc_GdccWorker_fat_ptr arg0 = gdcc_GdccWorker_fat_ptr_from_raw(*((const GDExtensionObjectPtr *)p_args[0]));"), entryHeader);
            assertFalse(entryHeader.contains("(*((gdcc_Node_fat_ptr*)p_args[0]))"), entryHeader);
            assertFalse(entryHeader.contains("(*((gdcc_GdccWorker_fat_ptr*)p_args[0]))"), entryHeader);
            // Object returns: validated raw transfer, not fat write into r_return.
            assertTrue(entryHeader.contains("*((GDExtensionObjectPtr *)r_return) = gdcc_Node_fat_ptr_live_object(r);"), entryHeader);
            assertTrue(entryHeader.contains("*((GDExtensionObjectPtr *)r_return) = gdcc_GdccWorker_fat_ptr_live_object(r);"), entryHeader);
            assertFalse(entryHeader.contains("*((gdcc_Node_fat_ptr*)r_return)"), entryHeader);
            // call_func uses owner fat self, not raw void* p_instance as first arg.
            assertTrue(entryHeader.contains("self_fat ="), entryHeader);
            assertTrue(entryHeader.contains("_from_raw(Worker_object_ptr((Worker*)p_instance))"), entryHeader);
        }

        @Test
        @DisplayName("registered wrappers do not destroy object fat pointer locals with object_destroy")
        void registeredWrappersDoNotDestroyObjectLocals() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertFalse(entryHeader.contains("godot_object_destroy(&arg0)"), entryHeader);
            assertFalse(entryHeader.contains("godot_object_destroy(&r)"), entryHeader);
        }
    }

    private static String prepareBlockOutputForReturn(GdObjectType returnType) {
        var workerClass = newClass("Worker");
        var func = new LirFunctionDef("return_object");
        func.setReturnType(returnType);
        var helper = newHelper();
        var builder = new CBodyBuilder(helper, workerClass, func);
        builder.beginBasicBlock("__prepare__");
        return builder.build();
    }

    private static String generateRegisteredWrapperHeader() {
        var workerClass = newClass("Worker");

        var takeEngine = new LirFunctionDef("take_engine");
        takeEngine.setReturnType(GdVoidType.VOID);
        takeEngine.addParameter(new LirParameterDef("value", ENGINE_NODE, null, takeEngine));
        addEntryReturn(takeEngine);
        workerClass.addFunction(takeEngine);

        var takeGdcc = new LirFunctionDef("take_gdcc");
        takeGdcc.setReturnType(GdVoidType.VOID);
        takeGdcc.addParameter(new LirParameterDef("value", GDCC_WORKER, null, takeGdcc));
        addEntryReturn(takeGdcc);
        workerClass.addFunction(takeGdcc);

        addObjectReturnFunction(workerClass, "return_engine", ENGINE_NODE);
        addObjectReturnFunction(workerClass, "return_gdcc", GDCC_WORKER);

        return generateEntryHeader(List.of(workerClass, newClass("GdccWorker")));
    }

    private static String generateEntryHeader(List<LirClassDef> gdccClasses) {
        return generateFile(gdccClasses, "entry.h");
    }

    private static String generateFile(List<LirClassDef> gdccClasses, String filePath) {
        var module = new LirModule("object_representation_module", gdccClasses);
        var codegen = newCodegen(module, gdccClasses);
        return codegen.generate().stream()
                .filter(file -> file.filePath().equals(filePath))
                .findFirst()
                .map(GeneratedFile::contentWriter)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow();
    }

    private static CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api());
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        return codegen;
    }

    private static CGenHelper newHelper() {
        var classRegistry = new ClassRegistry(api());
        var classDefs = List.of(newClass("Worker"), newClass("GdccWorker"));
        for (var classDef : classDefs) {
            classRegistry.addGdccClass(classDef);
        }
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CGenHelper(new CodegenContext(projectInfo, classRegistry), classDefs);
    }

    private static LirClassDef newClass(String name) {
        return new LirClassDef(name, "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private static void addEntryReturn(LirFunctionDef func) {
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private static void addObjectReturnFunction(LirClassDef classDef, String name, GdObjectType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.createAndAddVariable("result", returnType);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("result"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        classDef.addFunction(func);
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
}
