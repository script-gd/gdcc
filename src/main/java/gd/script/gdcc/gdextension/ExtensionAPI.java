package gd.script.gdcc.gdextension;

import java.util.List;
import java.util.Objects;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record ExtensionAPI(
        @SerializedName("header") ExtensionHeader header,
        @SerializedName("builtin_class_sizes") List<ExtensionBuiltinClassSizes> builtinClassSizes,
        @SerializedName("builtin_class_member_offsets") List<ExtensionBuiltinClassMemberOffsets> builtinClassMemberOffsets,
        @SerializedName("global_constants") List<ExtensionGlobalConstant> globalConstants,
        @SerializedName("global_enums") List<ExtensionGlobalEnum> globalEnums,
        @SerializedName("utility_functions") List<ExtensionUtilityFunction> utilityFunctions,
        @SerializedName("builtin_classes") List<ExtensionBuiltinClass> builtinClasses,
        @SerializedName("classes") List<ExtensionGdClass> classes,
        @SerializedName("singletons") List<ExtensionSingleton> singletons,
        @SerializedName("native_structures") List<ExtensionNativeStructure> nativeStructures
) {
    public ExtensionAPI(ExtensionHeader header,
                        List<ExtensionBuiltinClassSizes> builtinClassSizes,
                        List<ExtensionBuiltinClassMemberOffsets> builtinClassMemberOffsets,
                        List<ExtensionGlobalEnum> globalEnums,
                        List<ExtensionUtilityFunction> utilityFunctions,
                        List<ExtensionBuiltinClass> builtinClasses,
                        List<ExtensionGdClass> classes,
                        List<ExtensionSingleton> singletons,
                        List<ExtensionNativeStructure> nativeStructures) {
        this(
                header,
                builtinClassSizes,
                builtinClassMemberOffsets,
                List.of(),
                globalEnums,
                utilityFunctions,
                builtinClasses,
                classes,
                singletons,
                nativeStructures
        );
    }

    /// Find ABI size metadata for a builtin under one Godot build configuration.
    ///
    /// The build configuration dimension is part of the ABI key; callers must not fall back to another
    /// configuration when a lookup misses.
    public @Nullable Integer findBuiltinClassSize(
            @NotNull String buildConfiguration,
            @NotNull String builtinClassName
    ) {
        Objects.requireNonNull(buildConfiguration, "buildConfiguration must not be null");
        Objects.requireNonNull(builtinClassName, "builtinClassName must not be null");
        for (var sizesByConfiguration : builtinClassSizes) {
            if (!buildConfiguration.equals(sizesByConfiguration.buildConfiguration())) {
                continue;
            }
            for (var size : sizesByConfiguration.sizes()) {
                if (builtinClassName.equals(size.name())) {
                    return size.size();
                }
            }
            return null;
        }
        return null;
    }

    /// Require ABI size metadata for a builtin under one Godot build configuration.
    public int requireBuiltinClassSize(
            @NotNull String buildConfiguration,
            @NotNull String builtinClassName
    ) {
        var size = findBuiltinClassSize(buildConfiguration, builtinClassName);
        if (size == null) {
            throw new IllegalStateException(
                    "Missing builtin class size metadata: buildConfiguration='"
                            + buildConfiguration + "', builtinClass='" + builtinClassName + "'"
            );
        }
        return size;
    }

    /// Find ABI member layout metadata for a builtin member under one Godot build configuration.
    ///
    /// This is layout-only metadata. It must not be used as the builtin property surface; that surface
    /// still comes from `ExtensionBuiltinClass.members()`.
    public @Nullable BuiltinClassMemberLayout findBuiltinClassMemberLayout(
            @NotNull String buildConfiguration,
            @NotNull String builtinClassName,
            @NotNull String memberName
    ) {
        Objects.requireNonNull(buildConfiguration, "buildConfiguration must not be null");
        Objects.requireNonNull(builtinClassName, "builtinClassName must not be null");
        Objects.requireNonNull(memberName, "memberName must not be null");
        for (var offsetsByConfiguration : builtinClassMemberOffsets) {
            if (!buildConfiguration.equals(offsetsByConfiguration.buildConfiguration())) {
                continue;
            }
            for (var classData : offsetsByConfiguration.classes()) {
                if (!builtinClassName.equals(classData.name())) {
                    continue;
                }
                for (var memberData : classData.members()) {
                    if (memberName.equals(memberData.member())) {
                        return new BuiltinClassMemberLayout(memberData.offset(), memberData.meta());
                    }
                }
                return null;
            }
            return null;
        }
        return null;
    }

    /// Require ABI member layout metadata for a builtin member under one Godot build configuration.
    public @NotNull BuiltinClassMemberLayout requireBuiltinClassMemberLayout(
            @NotNull String buildConfiguration,
            @NotNull String builtinClassName,
            @NotNull String memberName
    ) {
        var layout = findBuiltinClassMemberLayout(buildConfiguration, builtinClassName, memberName);
        if (layout == null) {
            throw new IllegalStateException(
                    "Missing builtin class member layout metadata: buildConfiguration='"
                            + buildConfiguration + "', builtinClass='" + builtinClassName
                            + "', member='" + memberName + "'"
            );
        }
        return layout;
    }

    public record BuiltinClassMemberLayout(int offset, @NotNull String meta) {
    }
}
