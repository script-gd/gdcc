package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.GodotBindingSymbol;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.backend.c.gen.binding.usage.EngineConstructorUsage;
import gd.script.gdcc.backend.c.gen.fatptr.CObjectFatPtrCollector;
import gd.script.gdcc.backend.c.gen.fatptr.ObjectFatPtrSpec;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors object fat pointer spec collection, typedef declaration, unknown fail-fast, and
/// role-specific renderers. Internal object storage uses per-type fat pointers; bare/raw renderers
/// serve ABI/layout edges only.
class ObjectFatPtrDeclarationTest {
    private static final GdObjectType ENGINE_NODE = new GdObjectType("Node");
    private static final GdObjectType ENGINE_REFCOUNTED = new GdObjectType("RefCounted");
    private static final GdObjectType GDCC_WORKER = new GdObjectType("GdccWorker");
    private static final GdObjectType UNKNOWN_OBJECT = new GdObjectType("UnknownObject");

    @Nested
    @DisplayName("ObjectFatPtrSpec")
    class SpecTests {
        @Test
        @DisplayName("engine specs keep godot raw pointer field and no wrapper helper")
        void engineSpec() {
            var spec = ObjectFatPtrSpec.forObjectType(newRegistry(), ENGINE_NODE, "test");

            assertEquals("Node", spec.canonicalClassName());
            assertEquals("gdcc_Node_fat_ptr", spec.fatPtrTypeName());
            assertEquals("godot_Node *", spec.pointerCType());
            assertEquals(ObjectFatPtrSpec.Kind.ENGINE, spec.kind());
            assertEquals(RefCountedStatus.NO, spec.refCountedStatus());
            assertNull(spec.objectPtrHelperName());
        }

        @Test
        @DisplayName("GDCC specs keep wrapper pointer field and object_ptr helper")
        void gdccSpec() {
            var workerClass = newClass("Worker");
            var registry = newRegistry();
            registry.addGdccClass(workerClass);

            var spec = ObjectFatPtrSpec.forObjectType(registry, new GdObjectType("Worker"), "test");

            assertEquals("Worker", spec.canonicalClassName());
            assertEquals("gdcc_Worker_fat_ptr", spec.fatPtrTypeName());
            assertEquals("Worker *", spec.pointerCType());
            assertEquals(ObjectFatPtrSpec.Kind.GDCC, spec.kind());
            assertEquals(RefCountedStatus.YES, spec.refCountedStatus());
            assertEquals("Worker_object_ptr", spec.objectPtrHelperName());
        }

        @Test
        @DisplayName("unknown object types fail fast with the exposing surface")
        void unknownSpecFailsFast() {
            var error = assertThrows(
                    IllegalStateException.class,
                    () -> ObjectFatPtrSpec.forObjectType(newRegistry(), UNKNOWN_OBJECT, "property 'Worker.bad'")
            );
            assertTrue(error.getMessage().contains("UnknownObject"), error.getMessage());
            assertTrue(error.getMessage().contains("property 'Worker.bad'"), error.getMessage());
        }

        @Test
        @DisplayName("pointerCType must stay template-concatenation safe")
        void pointerCTypeMustEndWithSpaceStar() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ObjectFatPtrSpec(
                            ENGINE_NODE,
                            "Node",
                            "Node",
                            "gdcc_Node_fat_ptr",
                            "godot_Node*",
                            ObjectFatPtrSpec.Kind.ENGINE,
                            RefCountedStatus.NO,
                            null
                    )
            );
        }
    }

    @Nested
    @DisplayName("CObjectFatPtrCollector")
    class CollectorTests {
        @Test
        @DisplayName("module surfaces are deduplicated and sorted by ref type name")
        void dedupAndSort() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var func = new LirFunctionDef("use_objects");
            func.setReturnType(ENGINE_REFCOUNTED);
            func.addParameter(new LirParameterDef("node", ENGINE_NODE, null, func));
            func.createAndAddVariable("temp", ENGINE_REFCOUNTED);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var specs = CObjectFatPtrCollector.collect(
                    new LirModule("test_module", List.of(workerClass, gdccWorkerClass)),
                    registryForClasses(workerClass, gdccWorkerClass)
            );

            assertEquals(
                    List.of("gdcc_GdccWorker_fat_ptr", "gdcc_Node_fat_ptr", "gdcc_RefCounted_fat_ptr", "gdcc_Worker_fat_ptr"),
                    specs.stream().map(ObjectFatPtrSpec::fatPtrTypeName).toList()
            );
        }

        @Test
        @DisplayName("properties, captures, variables, and container leaves are collected")
        void scansAllModuleSurfaces() {
            var workerClass = newClass("Worker");
            workerClass.addProperty(new LirPropertyDef("nodes", new GdArrayType(ENGINE_NODE)));
            workerClass.addProperty(new LirPropertyDef(
                    "lookup",
                    new GdDictionaryType(GdStringType.STRING, GDCC_WORKER)
            ));
            var lambda = new LirFunctionDef("lambda");
            lambda.setLambda(true);
            lambda.setReturnType(GdVoidType.VOID);
            lambda.addCapture(new LirCaptureDef("captured", ENGINE_REFCOUNTED, lambda));
            addEntryReturn(lambda);
            workerClass.addFunction(lambda);

            var specs = CObjectFatPtrCollector.collect(
                    new LirModule("test_module", List.of(workerClass, newClass("GdccWorker"))),
                    registryForClasses(workerClass, newClass("GdccWorker"))
            );

            var fatPtrTypeNames = specs.stream().map(ObjectFatPtrSpec::fatPtrTypeName).toList();
            assertTrue(fatPtrTypeNames.contains("gdcc_Node_fat_ptr"), fatPtrTypeNames.toString());
            assertTrue(fatPtrTypeNames.contains("gdcc_RefCounted_fat_ptr"), fatPtrTypeNames.toString());
            assertTrue(fatPtrTypeNames.contains("gdcc_GdccWorker_fat_ptr"), fatPtrTypeNames.toString());
        }

        @Test
        @DisplayName("unknown object surfaces fail fast before declaration")
        void unknownModuleSurfaceFailsFast() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("use_unknown");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("unknown", UNKNOWN_OBJECT);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var error = assertThrows(
                    IllegalStateException.class,
                    () -> CObjectFatPtrCollector.collect(
                            new LirModule("test_module", List.of(workerClass)),
                            registryForClasses(workerClass)
                    )
            );
            assertTrue(error.getMessage().contains("UnknownObject"), error.getMessage());
            assertTrue(error.getMessage().contains("function 'Worker.use_unknown' variable 'unknown'"), error.getMessage());
        }

        @Test
        @DisplayName("engine binding surfaces contribute object specs")
        void engineBindingSurfaces() {
            var resolved = new BackendMethodCallResolver.ResolvedMethodCall(
                    BackendMethodCallResolver.DispatchMode.ENGINE,
                    "duplicate",
                    "Node",
                    ENGINE_NODE,
                    "gdcc_engine_call_node_duplicate",
                    ENGINE_REFCOUNTED,
                    List.of(new BackendMethodCallResolver.MethodParamSpec(
                            "source",
                            ENGINE_NODE,
                            BackendMethodCallResolver.DefaultArgKind.NONE,
                            null,
                            null,
                            null
                    )),
                    null,
                    false,
                    false
            );
            var constructor = new EngineConstructorUsage("Node", "Node", "Node", false);
            var singleton = new ModuleLocalGodotBinding.Singleton(
                    new GodotBindingSymbol(
                            GodotBindingSymbol.Family.SINGLETON,
                            "Node",
                            "NodeSingleton",
                            "gdcc_singleton_NodeSingleton",
                            "godot_Node *",
                            List.of(),
                            false,
                            null,
                            List.of()
                    ),
                    "NodeSingleton",
                    "Node"
            );

            var specs = CObjectFatPtrCollector.collect(
                    new LirModule("test_module", List.of()),
                    newRegistry(),
                    List.of(resolved),
                    List.of(constructor),
                    List.of(singleton)
            );

            assertEquals(
                    List.of("gdcc_Node_fat_ptr", "gdcc_RefCounted_fat_ptr"),
                    specs.stream().map(ObjectFatPtrSpec::fatPtrTypeName).toList()
            );
        }

        @Test
        @DisplayName("sanitized identifier collisions fail fast")
        void identifierCollisionFailsFast() {
            var dashedClass = newClass("A-B");
            var underscoreClass = newClass("A_B");

            var error = assertThrows(
                    IllegalStateException.class,
                    () -> CObjectFatPtrCollector.collect(
                            new LirModule("test_module", List.of(dashedClass, underscoreClass)),
                            registryForClasses(dashedClass, underscoreClass)
                    )
            );
            assertTrue(error.getMessage().contains("gdcc_A_B_fat_ptr"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("Role-specific renderers")
    class RendererTests {
        @Test
        @DisplayName("fat pointer roles stay distinct from raw ABI roles")
        void roleRenderers() {
            var helper = newHelper();

            assertEquals("gdcc_Node_fat_ptr", helper.renderObjectFatPtrStorageType(ENGINE_NODE));
            assertEquals("gdcc_Node_fat_ptr", helper.renderObjectFatPtrParameterType(ENGINE_NODE));
            assertEquals("gdcc_Node_fat_ptr *", helper.renderObjectFatPtrStorageAddressType(ENGINE_NODE));
            assertEquals("godot_Node *", helper.renderObjectRawPointerType(ENGINE_NODE));
            assertEquals("GDExtensionObjectPtr", helper.renderObjectReceiverType(ENGINE_NODE));

            assertEquals("GdccWorker *", helper.renderObjectRawPointerType(GDCC_WORKER));
            assertEquals("gdcc_GdccWorker_fat_ptr", helper.renderObjectFatPtrStorageType(GDCC_WORKER));
        }

        @Test
        @DisplayName("unknown object types fail fast in every role renderer")
        void unknownRenderersFailFast() {
            var helper = newHelper();

            assertThrows(IllegalStateException.class, () -> helper.renderObjectFatPtrStorageType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectFatPtrParameterType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectFatPtrStorageAddressType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectRawPointerType(UNKNOWN_OBJECT));
            assertThrows(IllegalStateException.class, () -> helper.renderObjectReceiverType(UNKNOWN_OBJECT));
        }
    }

    @Nested
    @DisplayName("Generated header declarations")
    class HeaderDeclarationTests {
        @Test
        @DisplayName("object_fat_ptr_types.h declares typedefs once, sorted, and after GDCC forward declarations")
        void objectFatPtrTypesHeaderDeclaresTypedefs() {
            var workerClass = newClass("Worker");
            var gdccWorkerClass = newClass("GdccWorker");
            var takeNode = new LirFunctionDef("take_node");
            takeNode.setReturnType(GdVoidType.VOID);
            takeNode.addParameter(new LirParameterDef("value", ENGINE_NODE, null, takeNode));
            addEntryReturn(takeNode);
            workerClass.addFunction(takeNode);

            var makeRef = new LirFunctionDef("make_ref");
            makeRef.setReturnType(ENGINE_REFCOUNTED);
            makeRef.createAndAddVariable("result", ENGINE_REFCOUNTED);
            var makeRefEntry = new LirBasicBlock("entry");
            makeRefEntry.appendInstruction(new ReturnInsn("result"));
            makeRef.addBasicBlock(makeRefEntry);
            makeRef.setEntryBlockId("entry");
            workerClass.addFunction(makeRef);

            var header = generateFile(List.of(workerClass, gdccWorkerClass), "object_fat_ptr_types.h");

            var workerForwardDecl = header.indexOf("typedef struct Worker Worker;");
            var fatPtrBlock = header.indexOf("// Object fat pointer declarations");
            assertTrue(workerForwardDecl >= 0, header);
            assertTrue(fatPtrBlock > workerForwardDecl, header);

            assertContainsOnce(header, "typedef struct gdcc_GdccWorker_fat_ptr");
            assertContainsOnce(header, "typedef struct gdcc_Node_fat_ptr");
            assertContainsOnce(header, "typedef struct gdcc_RefCounted_fat_ptr");
            assertContainsOnce(header, "typedef struct gdcc_Worker_fat_ptr");

            assertTrue(header.contains("godot_Node *ptr;"), header);
            assertTrue(header.contains("Worker *ptr;"), header);
            assertTrue(header.contains("GDObjectInstanceID instance_id;"), header);
            assertTrue(header.contains("} gdcc_Node_fat_ptr;"), header);

            var gdccWorker = header.indexOf("typedef struct gdcc_GdccWorker_fat_ptr");
            var node = header.indexOf("typedef struct gdcc_Node_fat_ptr");
            var refCounted = header.indexOf("typedef struct gdcc_RefCounted_fat_ptr");
            var worker = header.indexOf("typedef struct gdcc_Worker_fat_ptr");
            assertTrue(gdccWorker < node, header);
            assertTrue(node < refCounted, header);
            assertTrue(refCounted < worker, header);
        }

        @Test
        @DisplayName("entry.h includes object_fat_ptr_types.h before wrapper definitions and keeps no inline fat pointers")
        void entryHeaderIncludesObjectRefTypes() {
            var workerClass = newClass("Worker");
            var noop = new LirFunctionDef("noop");
            noop.setReturnType(GdVoidType.VOID);
            addEntryReturn(noop);
            workerClass.addFunction(noop);

            var header = generateFile(List.of(workerClass), "entry.h");

            var forwardDecl = header.indexOf("typedef struct Worker Worker;");
            var objectFatPtrTypesInclude = header.indexOf("#include \"object_fat_ptr_types.h\"");
            var structDef = header.indexOf("struct Worker {");
            assertTrue(forwardDecl >= 0, header);
            assertTrue(objectFatPtrTypesInclude > forwardDecl, header);
            assertTrue(structDef > objectFatPtrTypesInclude, header);
            assertFalse(header.contains("// Object fat pointer declarations"), header);
            assertFalse(header.contains("// Object fat pointer helpers"), header);
            assertFalse(header.contains("// Object fat pointer upcast helpers"), header);
            assertFalse(header.contains("typedef struct gdcc_Worker_fat_ptr"), header);
        }

        @Test
        @DisplayName("engine_method_binds.h is included after object_fat_ptr_types.h and before binding wrappers")
        void entryHeaderOrdersGeneratedIncludes() {
            var workerClass = newClass("Worker");
            var noop = new LirFunctionDef("noop");
            noop.setReturnType(GdVoidType.VOID);
            addEntryReturn(noop);
            workerClass.addFunction(noop);

            var header = generateFile(List.of(workerClass), "entry.h");

            var objectFatPtrTypesInclude = header.indexOf("#include \"object_fat_ptr_types.h\"");
            var engineBindsInclude = header.indexOf("#include \"engine_method_binds.h\"");
            var bindingHelpers = header.indexOf("// Method binding helpers");
            assertTrue(objectFatPtrTypesInclude >= 0, header);
            assertTrue(engineBindsInclude > objectFatPtrTypesInclude, header);
            assertTrue(engineBindsInclude < bindingHelpers, header);
        }

        @Test
        @DisplayName("modules without object surfaces emit an empty object_fat_ptr_types.h")
        void objectFatPtrTypesHeaderOmitsFatPtrBlockWhenNoObjectSpecs() {
            var header = generateFile(List.of(), "object_fat_ptr_types.h");

            assertFalse(header.contains("// Object fat pointer declarations"), header);
        }

        @Test
        @DisplayName("full generation fails fast when a module surface exposes an unknown object type")
        void generateFailsFastForUnknownObject() {
            var workerClass = newClass("Worker");
            var func = new LirFunctionDef("use_unknown");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("unknown", UNKNOWN_OBJECT);
            addEntryReturn(func);
            workerClass.addFunction(func);

            var codegen = newCodegen(new LirModule("fat_ptr_declaration_module", List.of(workerClass)), List.of(workerClass));

            // FreeMarker wraps the Java fail-fast as RuntimeException during template render.
            var error = assertThrows(RuntimeException.class, codegen::generate);
            var foundUnknown = false;
            for (var cursor = (Throwable) error; cursor != null; cursor = cursor.getCause()) {
                if (String.valueOf(cursor.getMessage()).contains("UnknownObject")) {
                    foundUnknown = true;
                    break;
                }
            }
            assertTrue(foundUnknown, String.valueOf(error));
        }
    }

    @Nested
    @DisplayName("Generated fat pointer helpers")
    class HelperGenerationTests {
        @Test
        @DisplayName("object_fat_ptr_types.h generates conversion, live, and Variant helpers for each spec")
        void perTypeHelpersAreGenerated() {
            var workerClass = newClass("Worker");
            var useObjects = new LirFunctionDef("use_objects");
            useObjects.setReturnType(GdVoidType.VOID);
            useObjects.addParameter(new LirParameterDef("node", ENGINE_NODE, null, useObjects));
            useObjects.addParameter(new LirParameterDef("worker", GDCC_WORKER, null, useObjects));
            addEntryReturn(useObjects);
            workerClass.addFunction(useObjects);

            var header = generateFile(List.of(workerClass, newClass("GdccWorker")), "object_fat_ptr_types.h");

            assertTrue(header.contains("// Object fat pointer helpers"), header);
            assertContainsOnce(header, "static inline gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_raw(GDExtensionObjectPtr raw)");
            assertContainsOnce(header, "static inline gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_variant(const godot_Variant *value)");
            assertContainsOnce(header, "static inline GDExtensionObjectPtr gdcc_Node_fat_ptr_live_object(gdcc_Node_fat_ptr value)");
            assertContainsOnce(header, "static inline godot_Node *gdcc_Node_fat_ptr_live_ptr(gdcc_Node_fat_ptr value)");
            assertContainsOnce(header, "static inline godot_Variant gdcc_Node_fat_ptr_to_variant(gdcc_Node_fat_ptr value)");
            assertContainsOnce(header, "static inline gdcc_GdccWorker_fat_ptr gdcc_GdccWorker_fat_ptr_from_raw(GDExtensionObjectPtr raw)");

            assertTrue(header.contains("GDObjectInstanceID id = godot_variant_get_object_instance_id(value);"), header);
            assertTrue(header.contains("gdcc_Node_fat_ptr result = { ptr, id };"), header);
            assertTrue(header.contains("gdcc_Node_fat_ptr result = { NULL, id };"), header);
        }

        @Test
        @DisplayName("static RefCounted live helper uses the cached pointer and no ObjectDB lookup")
        void refCountedYesLiveHelperSkipsObjectDb() {
            var workerClass = newClass("Worker");
            var useWorker = new LirFunctionDef("use_worker");
            useWorker.setReturnType(GdVoidType.VOID);
            useWorker.addParameter(new LirParameterDef("worker", GDCC_WORKER, null, useWorker));
            addEntryReturn(useWorker);
            workerClass.addFunction(useWorker);

            var header = generateFile(List.of(workerClass, newClass("GdccWorker")), "object_fat_ptr_types.h");

            var liveObjectStart = header.indexOf("static inline GDExtensionObjectPtr gdcc_GdccWorker_fat_ptr_live_object(");
            var liveObjectEnd = header.indexOf("static inline", liveObjectStart + 1);
            assertTrue(liveObjectStart >= 0, header);
            var liveObject = header.substring(liveObjectStart, liveObjectEnd);

            assertTrue(liveObject.contains("if (unlikely(value.instance_id == 0 || value.ptr == NULL))"), liveObject);
            assertTrue(liveObject.contains("return GdccWorker_object_ptr(value.ptr);"), liveObject);
            assertFalse(liveObject.contains("gdcc_object_live_ptr(value.instance_id)"), liveObject);
        }

        @Test
        @DisplayName("static non-RefCounted live helper validates through ObjectDB")
        void nonRefCountedLiveHelperUsesObjectDb() {
            var workerClass = newClass("Worker");
            var useNode = new LirFunctionDef("use_node");
            useNode.setReturnType(GdVoidType.VOID);
            useNode.addParameter(new LirParameterDef("node", ENGINE_NODE, null, useNode));
            addEntryReturn(useNode);
            workerClass.addFunction(useNode);

            var header = generateFile(List.of(workerClass), "object_fat_ptr_types.h");

            var liveObjectStart = header.indexOf("static inline GDExtensionObjectPtr gdcc_Node_fat_ptr_live_object(");
            var liveObjectEnd = header.indexOf("static inline", liveObjectStart + 1);
            assertTrue(liveObjectStart >= 0, header);
            var liveObject = header.substring(liveObjectStart, liveObjectEnd);

            assertTrue(liveObject.contains("return gdcc_object_live_ptr(value.instance_id);"), liveObject);
            assertFalse(liveObject.contains("value.ptr"), liveObject);
        }

        @Test
        @DisplayName("upcast helpers preserve instance ID and rebuild target pointers")
        void upcastHelpersAreGeneratedForAssignablePairs() {
            var workerClass = newClass("Worker");
            var useBoth = new LirFunctionDef("use_both");
            useBoth.setReturnType(GdVoidType.VOID);
            useBoth.addParameter(new LirParameterDef("worker", GDCC_WORKER, null, useBoth));
            useBoth.addParameter(new LirParameterDef("ref", ENGINE_REFCOUNTED, null, useBoth));
            addEntryReturn(useBoth);
            workerClass.addFunction(useBoth);

            var header = generateFile(List.of(workerClass, newClass("GdccWorker")), "object_fat_ptr_types.h");

            assertTrue(header.contains(
                    "static inline gdcc_RefCounted_fat_ptr gdcc_GdccWorker_fat_ptr_upcast_to_RefCounted(gdcc_GdccWorker_fat_ptr value)"
            ), header);
            assertTrue(header.contains("result.instance_id = value.instance_id;"), header);
            assertFalse(header.contains("gdcc_RefCounted_fat_ptr_upcast_to_GdccWorker"), header);
        }

        @Test
        @DisplayName("modules without object specs emit no per-type helpers")
        void noSpecsNoHelpers() {
            var header = generateFile(List.of(), "object_fat_ptr_types.h");

            assertFalse(header.contains("// Object fat pointer helpers"), header);
        }
    }

    private static void assertContainsOnce(String haystack, String needle) {
        assertEquals(1, countOccurrences(haystack, needle), haystack);
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String generateFile(List<LirClassDef> gdccClasses, String filePath) {
        var module = new LirModule("fat_ptr_declaration_module", gdccClasses);
        var codegen = newCodegen(module, gdccClasses);
        return codegen.generate().stream()
                .filter(file -> file.filePath().equals(filePath))
                .findFirst()
                .map(GeneratedFile::contentWriter)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow();
    }

    private static CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        var classRegistry = newRegistry();
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
        var classDefs = List.of(newClass("Worker"), newClass("GdccWorker"));
        var classRegistry = newRegistry();
        for (var classDef : classDefs) {
            classRegistry.addGdccClass(classDef);
        }
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CGenHelper(new CodegenContext(projectInfo, classRegistry), classDefs);
    }

    private static ClassRegistry registryForClasses(LirClassDef... classes) {
        var registry = newRegistry();
        for (var classDef : classes) {
            registry.addGdccClass(classDef);
        }
        return registry;
    }

    private static ClassRegistry newRegistry() {
        return new ClassRegistry(api());
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
