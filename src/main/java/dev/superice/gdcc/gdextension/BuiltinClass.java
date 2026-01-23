package dev.superice.gdcc.gdextension;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public record BuiltinClass(
        @SerializedName("name") String name,
        @SerializedName("is_keyed") boolean isKeyed,
        @SerializedName("operators") List<ClassOperator> operators,
        @SerializedName("methods") List<ClassMethod> methods,
        @SerializedName("enums") List<ClassEnum> enums,
        @SerializedName("constructors") List<ConstructorInfo> constructors,
        @SerializedName("properties") List<PropertyInfo> properties,
        @SerializedName("constants") List<ConstantInfo> constants
) {
    public record ClassOperator(String name, String rightType, String returnType) { }
    public record ClassMethod(
            String name,
            String returnType,
            boolean isVararg,
            boolean isConst,
            boolean isStatic,
            boolean isVirtual,
            long hash,
            List<FunctionArgument> arguments,
            List<Long> hashCompatibility,
            ReturnValue returnValue
    ) {
        public record ReturnValue(String type) { }
    }

    public record ClassEnum(String name, boolean isBitfield, List<EnumValue> values) { }

    public record ConstructorInfo(int index, List<FunctionArgument> arguments) { }

    public record PropertyInfo(String name, String type, boolean isReadable, boolean isWritable, String defaultValue) { }

    public record ConstantInfo(String name, String value) { }
}
