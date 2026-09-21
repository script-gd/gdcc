package gd.script.gdcc.lir;

import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.SignalDef;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LirClassDefTest {
    @Test
    public void getScriptConstants_defaultsToEmptyTable() {
        var classDef = new LirClassDef("Example", "RefCounted");

        assertTrue(classDef.getScriptConstants().isEmpty());
    }

    @Test
    public void classDefInterface_keepsEmptyConstantsDefaultForNonGdccClasses() {
        // Engine/builtin ClassDef implementations never override the constant table: their
        // constants keep flowing through the extension metadata channel instead.
        ClassDef nonGdccClass = new MinimalClassDefStub();

        assertTrue(nonGdccClass.getScriptConstants().isEmpty());
    }

    @Test
    public void addScriptConstant_appendsInDeclarationOrder() {
        var classDef = new LirClassDef("Example", "RefCounted");
        var first = constant("IDLE", 0);
        var second = constant("RUNNING", 1);
        var group = new GdScriptClassConstant(
                "State",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                new GdScriptEnumGroup("State", List.of(
                        new GdScriptEnumConstant("IDLE", 0, "State", "Example"),
                        new GdScriptEnumConstant("RUNNING", 1, "State", "Example")
                ), "Example")
        );

        classDef.addScriptConstant(first);
        classDef.addScriptConstant(second);
        classDef.addScriptConstant(group);

        assertEquals(List.of("IDLE", "RUNNING", "State"), classDef.getScriptConstants().stream()
                .map(GdScriptClassConstant::name)
                .toList());
    }

    @Test
    public void getScriptConstants_returnsUnmodifiableLiveView() {
        var classDef = new LirClassDef("Example", "RefCounted");
        classDef.addScriptConstant(constant("IDLE", 0));

        assertThrows(
                UnsupportedOperationException.class,
                () -> classDef.getScriptConstants().add(constant("BROKEN", 1))
        );
        // The view stays live: later additions are visible without re-acquiring it.
        var view = classDef.getScriptConstants();
        classDef.addScriptConstant(constant("RUNNING", 1));
        assertEquals(2, view.size());
    }

    @Test
    public void enumGroup_findMember_resolvesBySourceName() {
        var idle = new GdScriptEnumConstant("IDLE", 0, "State", "Example");
        var group = new GdScriptEnumGroup("State", List.of(idle), "Example");

        assertAll(
                () -> assertSame(idle, group.findMember("IDLE")),
                () -> assertNull(group.findMember("MISSING"))
        );
    }

    @Test
    public void enumGroup_membersAreDefensivelyCopied() {
        var members = new java.util.ArrayList<GdScriptEnumConstant>();
        members.add(new GdScriptEnumConstant("IDLE", 0, "State", "Example"));
        var group = new GdScriptEnumGroup("State", members, "Example");

        members.add(new GdScriptEnumConstant("SNEAKY", 1, "State", "Example"));

        assertAll(
                () -> assertEquals(1, group.members().size()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> group.members().add(new GdScriptEnumConstant("X", 2, "State", "Example"))
                )
        );
    }

    private static GdScriptClassConstant constant(String name, long value) {
        return new GdScriptClassConstant(
                name,
                GdIntType.INT,
                new GdScriptEnumConstant(name, value, null, "Example")
        );
    }

    /// Minimal non-GDCC `ClassDef` implementation used to pin the interface-level default table.
    private static final class MinimalClassDefStub implements ClassDef {
        @Override
        public @NotNull String getName() {
            return "EngineStub";
        }

        @Override
        public @NotNull String getSuperName() {
            return "";
        }

        @Override
        public boolean isAbstract() {
            return false;
        }

        @Override
        public boolean isTool() {
            return false;
        }

        @Override
        public @NotNull Map<String, String> getAnnotations() {
            return Map.of();
        }

        @Override
        public boolean hasAnnotation(@NotNull String key) {
            return false;
        }

        @Override
        public String getAnnotation(@NotNull String key) {
            return null;
        }

        @Override
        public @NotNull List<? extends SignalDef> getSignals() {
            return List.of();
        }

        @Override
        public @NotNull List<? extends PropertyDef> getProperties() {
            return List.of();
        }

        @Override
        public @NotNull List<? extends FunctionDef> getFunctions() {
            return List.of();
        }

        @Override
        public boolean hasFunction(@NotNull String functionName) {
            return false;
        }

        @Override
        public boolean isGdccClass() {
            return false;
        }
    }
}
