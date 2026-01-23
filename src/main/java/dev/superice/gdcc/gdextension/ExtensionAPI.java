package dev.superice.gdcc.gdextension;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public record ExtensionAPI(
        @SerializedName("header") ExtensionHeader header,
        @SerializedName("builtin_class_sizes") List<BuiltinClassSizes> builtinClassSizes,
        @SerializedName("builtin_class_member_offsets") List<BuiltinClassMemberOffsets> builtinClassMemberOffsets,
        @SerializedName("global_enums") List<GlobalEnum> globalEnums,
        @SerializedName("utility_functions") List<UtilityFunction> utilityFunctions,
        @SerializedName("builtin_classes") List<BuiltinClass> builtinClasses,
        @SerializedName("classes") List<GdClass> classes,
        @SerializedName("singletons") List<Singleton> singletons,
        @SerializedName("native_structures") List<NativeStructure> nativeStructures
) {
}
