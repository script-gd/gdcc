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

/// Phase 0 characterization for the current bare-pointer object value representation.
///
/// These tests anchor the pre-fat-reference baseline required by
/// `doc/module_impl/backend/object_value_fat_reference_implementation_plan.md`:
/// later phases must update the asserted C shapes deliberately instead of deleting them.
class ObjectValueRepresentationCharacterizationTest {
    private static final GdObjectType ENGINE_NODE = new GdObjectType("Node");
    private static final GdObjectType ENGINE_REFCOUNTED = new GdObjectType("RefCounted");
    private static final GdObjectType GDCC_WORKER = new GdObjectType("GdccWorker");
    private static final GdObjectType UNKNOWN_OBJECT = new GdObjectType("UnknownObject");

    @Nested
    @DisplayName("Type rendering baseline")
    class TypeRenderingBaseline {
        @Test
        @DisplayName("engine object storage and parameter types are bare godot pointers")
        void engineObjectRendersAsBareGodotPointer() {
            var helper = newHelper();

            assertEquals("godot_Node*", helper.renderGdTypeInC(ENGINE_NODE));
            assertEquals("godot_Node*", helper.renderGdTypeRefInC(ENGINE_NODE));
            assertEquals("godot_RefCounted*", helper.renderGdTypeInC(ENGINE_REFCOUNTED));
            assertEquals("godot_RefCounted*", helper.renderGdTypeRefInC(ENGINE_REFCOUNTED));
        }

        @Test
        @DisplayName("GDCC object storage and parameter types are bare wrapper pointers")
        void gdccObjectRendersAsBareWrapperPointer() {
            var helper = newHelper();

            assertEquals("GdccWorker*", helper.renderGdTypeInC(GDCC_WORKER));
            assertEquals("GdccWorker*", helper.renderGdTypeRefInC(GDCC_WORKER));
        }

        @Test
        @DisplayName("unknown object types still fall back to GDExtensionObjectPtr before phase 4")
        void unknownObjectFallsBackToGdExtensionObjectPtr() {
            var helper = newHelper();

            assertEquals("GDExtensionObjectPtr", helper.renderGdTypeInC(UNKNOWN_OBJECT));
            assertEquals("GDExtensionObjectPtr", helper.renderGdTypeRefInC(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("object values are passed directly because the current value already is a pointer")
        void objectValueRefDoesNotAddAddressOf() {
            var helper = newHelper();

            assertEquals("$obj", helper.renderValueRef(ENGINE_NODE, "$obj"));
            assertEquals("$obj", helper.renderValueRef(GDCC_WORKER, "$obj"));
            assertEquals("$obj", helper.renderValueRef(UNKNOWN_OBJECT, "$obj"));
        }

        @Test
        @DisplayName("object default values are bare NULL expressions")
        void objectDefaultValueIsBareNull() {
            assertEquals("NULL", CBodyBuilder.renderDefaultValueExpr(ENGINE_NODE));
            assertEquals("NULL", CBodyBuilder.renderDefaultValueExpr(GDCC_WORKER));
            assertEquals("NULL", CBodyBuilder.renderDefaultValueExpr(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("object pack helpers split only by GDCC vs non-GDCC representation")
        void objectPackHelpersUseCurrentBarePointerSurface() {
            var helper = newHelper();

            assertEquals("godot_new_Variant_with_Object", helper.renderPackFunctionName(ENGINE_NODE));
            assertEquals("gdcc_new_Variant_with_gdcc_Object", helper.renderPackFunctionName(GDCC_WORKER));
            assertEquals("godot_new_Variant_with_Object", helper.renderPackFunctionName(UNKNOWN_OBJECT));
        }

        @Test
        @DisplayName("object unpack helpers cast the raw Godot object result to the current static pointer")
        void objectUnpackHelpersUseCurrentCastSurface() {
            var helper = newHelper();

            assertEquals("(godot_Node*)godot_new_Object_with_Variant", helper.renderUnpackFunctionName(ENGINE_NODE));
            assertEquals("(GdccWorker*)godot_new_gdcc_Object_with_Variant", helper.renderUnpackFunctionName(GDCC_WORKER));
            assertEquals("(godot_UnknownObject*)godot_new_Object_with_Variant", helper.renderUnpackFunctionName(UNKNOWN_OBJECT));
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
    }

    @Nested
    @DisplayName("Function surface baseline")
    class FunctionSurfaceBaseline {
        @Test
        @DisplayName("object locals are initialized with bare NULL in __prepare__")
        void objectLocalInitializesToBareNull() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("use_object_local");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("node", ENGINE_NODE);
            func.createAndAddVariable("worker", GDCC_WORKER);
            func.createAndAddVariable("unknown", UNKNOWN_OBJECT);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var entrySource = generateFile(List.of(workerClass, newClass("GdccWorker")), "entry.c");

            assertTrue(entrySource.contains("$node = NULL;"), entrySource);
            assertTrue(entrySource.contains("$worker = NULL;"), entrySource);
            assertTrue(entrySource.contains("$unknown = NULL;"), entrySource);
        }

        @Test
        @DisplayName("object return slots are declared as bare pointers initialized to NULL")
        void objectReturnSlotIsBarePointerNull() {
            assertEquals(
                    "__prepare__: // __prepare__\ngodot_Node* _return_val = NULL;\n",
                    prepareBlockOutputForReturn(ENGINE_NODE)
            );
            assertEquals(
                    "__prepare__: // __prepare__\nGdccWorker* _return_val = NULL;\n",
                    prepareBlockOutputForReturn(GDCC_WORKER)
            );
            assertEquals(
                    "__prepare__: // __prepare__\nGDExtensionObjectPtr _return_val = NULL;\n",
                    prepareBlockOutputForReturn(UNKNOWN_OBJECT)
            );
        }

        @Test
        @DisplayName("internal function headers keep bare object parameter and return pointers")
        void internalFunctionHeadersUseBareObjectPointers() {
            var workerClass = newClass("Worker");

            var takeEngine = new LirFunctionDef("take_engine");
            takeEngine.setReturnType(GdVoidType.VOID);
            takeEngine.addParameter(new LirParameterDef("value", ENGINE_NODE, null, takeEngine));
            addEntryReturn(takeEngine);
            workerClass.addFunction(takeEngine);

            addObjectReturnFunction(workerClass, "return_engine", ENGINE_NODE);

            var entryHeader = generateEntryHeader(List.of(workerClass, newClass("GdccWorker")));

            assertTrue(entryHeader.contains("void Worker_take_engine("), entryHeader);
            assertTrue(entryHeader.contains("godot_Node* $value"), entryHeader);
            assertTrue(entryHeader.contains("godot_Node* Worker_return_engine("), entryHeader);
        }
    }

    @Nested
    @DisplayName("Registered wrapper baseline")
    class RegisteredWrapperBaseline {
        @Test
        @DisplayName("call_func unpacks object arguments into bare static pointers")
        void callFuncUnpacksObjectArgumentsIntoBarePointers() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertTrue(entryHeader.contains(
                    "const godot_Node* arg0 = (godot_Node*)godot_new_Object_with_Variant((GDExtensionVariantPtr)p_args[0]);"
            ), entryHeader);
            assertTrue(entryHeader.contains(
                    "const GdccWorker* arg0 = (GdccWorker*)godot_new_gdcc_Object_with_Variant((GDExtensionVariantPtr)p_args[0]);"
            ), entryHeader);
        }

        @Test
        @DisplayName("call_func packs object returns from bare static pointers")
        void callFuncPacksObjectReturnsFromBarePointers() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertTrue(entryHeader.contains("godot_Node* r = function(p_instance);"), entryHeader);
            assertTrue(entryHeader.contains("godot_Variant ret = godot_new_Variant_with_Object(r);"), entryHeader);
            assertTrue(entryHeader.contains("GdccWorker* r = function(p_instance);"), entryHeader);
            assertTrue(entryHeader.contains("godot_Variant ret = gdcc_new_Variant_with_gdcc_Object(r);"), entryHeader);
        }

        @Test
        @DisplayName("ptrcall passes raw object pointer slots and returns through raw pointer storage")
        void ptrcallUsesRawObjectPointerSlots() {
            var entryHeader = generateRegisteredWrapperHeader();

            assertTrue(entryHeader.contains("(function(p_instance, (*((godot_Node**)p_args[0]))));"), entryHeader);
            assertTrue(entryHeader.contains("(function(p_instance, (*((GdccWorker**)p_args[0]))));"), entryHeader);
            assertTrue(entryHeader.contains("*((godot_Node**)r_return) = function(p_instance);"), entryHeader);
            assertTrue(entryHeader.contains("*((GdccWorker**)r_return) = function(p_instance);"), entryHeader);
        }

        @Test
        @DisplayName("registered wrappers do not destroy bare object locals")
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
        var module = new LirModule("phase0_representation_module", gdccClasses);
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
}
