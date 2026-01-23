package dev.superice.gdcc.gdextension;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public record GdClass(
        @SerializedName("name") String name,
        @SerializedName("is_refcounted") boolean isRefcounted,
        @SerializedName("is_instantiable") boolean isInstantiable,
        @SerializedName("inherits") String inherits,
        @SerializedName("api_type") String apiType,
        @SerializedName("enums") List<ClassEnum> enums,
        @SerializedName("methods") List<ClassMethod> methods,
        @SerializedName("properties") List<PropertyInfo> properties,
        @SerializedName("constants") List<ConstantInfo> constants
) {
    public record ClassEnum(String name, boolean isBitfield, List<EnumValue> values) { }

    public record ClassMethod(
            String name,
            boolean isConst,
            boolean isVararg,
            boolean isStatic,
            boolean isVirtual,
            long hash,
            List<Long> hashCompatibility,
            ClassMethodReturn returnValue,
            List<FunctionArgument> arguments
    ) {
        public record ClassMethodReturn(String type) { }
    }

    public record ConstructorInfo(int index, List<FunctionArgument> arguments) { }

    public record PropertyInfo(String name, String type, boolean isReadable, boolean isWritable, String defaultValue) { }

    public record ConstantInfo(String name, String value) { }
}
