package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CDestructInsnGenTest {
    @Test
    @DisplayName("destruct string should call godot_String_destroy")
    void destructStringShouldCallDestroyFunction() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("destruct_string");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("s", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("s", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_String_destroy(&$s);"));
    }

    @Test
    @DisplayName("destruct compiler-only value should call gdcc destroy helper")
    void destructCompilerOnlyShouldCallDestroyHelper() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("destruct_compiler_only");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("iter", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_for_range_iter_destroy(&$iter);"), body);
        assertFalse(body.contains("godot_GdccForRangeIter_destroy"), body);
    }

    @Test
    @DisplayName("destruct refcounted GDCC object should use release_object")
    void destructRefCountedGdccObjectShouldUseReleaseObject() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var gdccObjectClass = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("destruct_obj");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("MyObject"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("obj", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass, gdccObjectClass));
        var codegen = newCodegen(module, List.of(workerClass, gdccObjectClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("release_object(gdcc_object_to_godot_object_ptr($obj, MyObject_object_ptr));"));
    }

    @Test
    @DisplayName("explicit destruct of non-refcounted engine object should use try_destroy_object")
    void destructNonRefCountedEngineObjectShouldUseTryDestroy() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass("Node", false, false, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(),
                List.of()
        );
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("destruct_node");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("node", new GdObjectType("Node"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("node", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), api);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("try_destroy_object($node);"));
    }

    @Test
    @DisplayName("AUTO_GENERATED destruct of non-refcounted engine object should be a no-op")
    void autoGeneratedDestructNonRefCountedEngineObjectShouldBeNoOp() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass("Node", false, false, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(),
                List.of()
        );
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("auto_destruct_node");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("node", new GdObjectType("Node"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new DestructInsn("node", LifecycleProvenance.AUTO_GENERATED));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), api);

        var body = codegen.generateFuncBody(workerClass, func);
        assertFalse(body.contains("try_destroy_object($node);"));
        assertFalse(body.contains("release_object($node);"));
        assertFalse(body.contains("try_release_object($node);"));
    }

    @Test
    @DisplayName("AUTO_GENERATED destruct of refcounted GDCC object should use release_object")
    void autoGeneratedDestructRefCountedGdccObjectShouldUseReleaseObject() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var gdccObjectClass = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("auto_destruct_obj");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("MyObject"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new DestructInsn("obj", LifecycleProvenance.AUTO_GENERATED));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass, gdccObjectClass));
        var codegen = newCodegen(module, List.of(workerClass, gdccObjectClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("release_object(gdcc_object_to_godot_object_ptr($obj, MyObject_object_ptr));"));
        assertFalse(body.contains("try_release_object("));
        assertFalse(body.contains("try_destroy_object("));
    }

    @Test
    @DisplayName("AUTO_GENERATED destruct of compiler-only value should still use gdcc destroy helper in __finally__")
    void autoGeneratedDestructCompilerOnlyShouldUseDestroyHelper() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("auto_destruct_compiler_only");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new DestructInsn("iter", LifecycleProvenance.AUTO_GENERATED));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_for_range_iter_destroy(&$iter);"), body);
        assertFalse(body.contains("godot_GdccForRangeIter_destroy"), body);
    }

    @Test
    @DisplayName("AUTO_GENERATED destruct of unknown object should use try_release_object")
    void autoGeneratedDestructUnknownObjectShouldUseTryReleaseObject() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("auto_destruct_unknown_obj");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("UnknownObject"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new DestructInsn("obj", LifecycleProvenance.AUTO_GENERATED));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("try_release_object($obj);"));
        assertFalse(body.contains("try_destroy_object($obj);"));
    }

    @Test
    @DisplayName("destruct unknown variable should throw InvalidInsnException")
    void destructUnknownVariableShouldThrow() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("destruct_missing");
        func.setReturnType(GdVoidType.VOID);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("missing", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("destruct AUTO_GENERATED outside __finally__ should throw InvalidInsnException")
    void destructAutoGeneratedOutsideFinallyShouldThrow() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("destruct_auto");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new DestructInsn("value", LifecycleProvenance.AUTO_GENERATED));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass), emptyApi());

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("AUTO_GENERATED"));
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses, ExtensionAPI api) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry, true);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
