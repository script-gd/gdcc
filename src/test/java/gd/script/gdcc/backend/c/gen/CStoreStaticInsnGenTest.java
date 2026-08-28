package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StoreStaticInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CStoreStaticInsnGenTest {
    @Test
    @DisplayName("store_static should be rejected with stable error")
    void shouldRejectStoreStaticInstruction() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_static_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new StoreStaticInsn("Node", "NOTIFICATION_ENTER_TREE", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api), module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Unsupported static store"));
    }

    private CodegenContext newContext(ExtensionAPI api) {
        var projectInfo = new ProjectInfo("store_static_test", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, new ClassRegistry(api));
    }

    // ==== GDCC script class static property branch ====

    private String generateBody(ExtensionAPI api, LirClassDef clazz, LirFunctionDef func) {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api), module);
        return codegen.generateFuncBody(clazz, func);
    }

    private LirFunctionDef setupStoreStaticFunction(GdType valueType, StoreStaticInsn instruction) {
        var func = new LirFunctionDef("store_static_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", valueType);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    @Test
    @DisplayName("store_static should write GDCC static property backing variable")
    void shouldStoreGdccStaticPropertyToBacking() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("count", GdIntType.INT, true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(GdIntType.INT, new StoreStaticInsn("Worker", "count", "value")));

        var body = generateBody(api, clazz, clazz.getFunctions().getFirst());

        assertTrue(body.contains("gdcc_static_Worker_count = $value;"), body);
    }

    @Test
    @DisplayName("store_static should write the declaring owner backing for inherited statics")
    void shouldStoreInheritedGdccStaticPropertyToOwnerBacking() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var baseClazz = new LirClassDef("Base", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("count", GdIntType.INT, true, null, null, null, Map.of())), List.of());
        var subClazz = new LirClassDef("Sub", "Base", false, false, Map.of(), List.of(), List.of(), List.of());
        subClazz.addFunction(setupStoreStaticFunction(GdIntType.INT, new StoreStaticInsn("Sub", "count", "value")));
        var module = new LirModule("test_module", List.of(baseClazz, subClazz));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api), module);

        var body = codegen.generateFuncBody(subClazz, subClazz.getFunctions().getFirst());

        assertTrue(body.contains("gdcc_static_Base_count = $value;"), body);
        assertTrue(!body.contains("gdcc_static_Sub_count"), body);
    }

    @Test
    @DisplayName("store_static should destroy the old destroyable value before overwriting")
    void shouldDestroyOldDestroyableStaticBeforeOverwrite() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("title", GdStringType.STRING, true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(GdStringType.STRING, new StoreStaticInsn("Worker", "title", "value")));

        var body = generateBody(api, clazz, clazz.getFunctions().getFirst());

        // Runtime overwrite of long-lived storage: destroy the old backing value, then assign
        // the new one (release-then-store parity with instance property stores).
        var destroyIndex = body.indexOf("godot_String_destroy(&gdcc_static_Worker_title)");
        var assignIndex = body.indexOf("gdcc_static_Worker_title =");
        assertTrue(destroyIndex >= 0, body);
        assertTrue(assignIndex > destroyIndex, body);
    }

    @Test
    @DisplayName("store_static should retain borrowed RefCounted values and release the old reference")
    void shouldRetainBorrowedAndReleaseOldOnRefCountedStaticStore() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("peer", new GdObjectType("Worker"), true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(new GdObjectType("Worker"), new StoreStaticInsn("Worker", "peer", "value")));

        var body = generateBody(api, clazz, clazz.getFunctions().getFirst());

        // Object overwrite order: capture old -> assign -> own BORROWED rhs -> release old.
        var captureIndex = body.indexOf("= gdcc_static_Worker_peer;");
        var assignIndex = body.indexOf("gdcc_static_Worker_peer = $value;");
        var ownIndex = body.indexOf("own_object", assignIndex);
        var releaseIndex = body.indexOf("release_object", ownIndex);
        assertTrue(captureIndex >= 0 && assignIndex > captureIndex && ownIndex > assignIndex && releaseIndex > ownIndex, body);
    }

    @Test
    @DisplayName("store_static should use try_* lifecycle forms for UNKNOWN RefCounted status")
    void shouldUseTryLifecycleFormsForUnknownRefCountedStaticStore() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("target", new GdObjectType("Object"), true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(new GdObjectType("Object"), new StoreStaticInsn("Worker", "target", "value")));

        var body = generateBody(api, clazz, clazz.getFunctions().getFirst());

        // `Object` root is UNKNOWN at ownership boundaries: runtime decides by ObjectID bit 63.
        assertTrue(body.contains("try_own_object"), body);
        assertTrue(body.contains("try_release_object"), body);
    }

    @Test
    @DisplayName("store_static should emit no own/release calls for NO RefCounted status")
    void shouldEmitNoLifecycleCallsForNonRefCountedStaticStore() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("node", new GdObjectType("Node"), true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(new GdObjectType("Node"), new StoreStaticInsn("Worker", "node", "value")));

        var body = generateBody(api, clazz, clazz.getFunctions().getFirst());

        // Non-RefCounted objects are user-managed: neither the BORROWED rhs retain nor the
        // old-value release may emit any lifecycle call (Godot-aligned no-op).
        assertTrue(body.contains("gdcc_static_Worker_node = $value;"), body);
        assertTrue(!body.contains("own_object"), body);
        assertTrue(!body.contains("release_object"), body);
    }

    @Test
    @DisplayName("store_static should reject unknown GDCC static property")
    void shouldRejectUnknownGdccStaticProperty() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("count", GdIntType.INT, true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(GdIntType.INT, new StoreStaticInsn("Worker", "missing", "value")));

        var ex = assertThrows(InvalidInsnException.class,
                () -> generateBody(api, clazz, clazz.getFunctions().getFirst()));
        assertTrue(ex.getMessage().contains("Static property 'missing' not found in GDCC class 'Worker'"));
    }

    @Test
    @DisplayName("store_static should reject value type not assignable to the static property type")
    void shouldRejectMismatchedGdccStaticStoreType() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(),
                List.of(new LirPropertyDef("count", GdIntType.INT, true, null, null, null, Map.of())), List.of());
        clazz.addFunction(setupStoreStaticFunction(GdStringType.STRING, new StoreStaticInsn("Worker", "count", "value")));

        var ex = assertThrows(InvalidInsnException.class,
                () -> generateBody(api, clazz, clazz.getFunctions().getFirst()));
        assertTrue(ex.getMessage().contains("is not assignable to static property type"));
    }
}
