package dev.superice.gdcc.gdextension;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Utility to load and serialize the extension_api.json as an ExtensionAPI instance.
public final class ExtensionApiLoader {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private ExtensionApiLoader() { }

    private static ExtensionAPI instance;

    /// Load the extension API from the classpath resource `/extension_api.json`.
    public static @NotNull ExtensionAPI loadFromResource() throws IOException {
        if (instance != null) {
            return instance;
        }
        instance = loadFromResource("/extension_api_451.json");
        return instance;
    }

    /// Load the extension API from the provided classpath resource path.
    public static @NotNull ExtensionAPI loadFromResource(@NotNull String resourcePath) throws IOException {
        var is = ExtensionApiLoader.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        try (var in = is; var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();

            var headerObj = root.has("header") ? root.getAsJsonObject("header") : null;
            var header = parseHeader(headerObj);

            List<GlobalEnum> globalEnums = root.has("global_enums") ? parseGlobalEnums(root.getAsJsonArray("global_enums")) : Collections.emptyList();
            List<UtilityFunction> utilityFunctions = root.has("utility_functions") ? parseUtilityFunctions(root.getAsJsonArray("utility_functions")) : Collections.emptyList();
            List<BuiltinClass> builtinClasses = root.has("builtin_classes") ? parseBuiltinClasses(root.getAsJsonArray("builtin_classes")) : Collections.emptyList();
            List<GdClass> classes = root.has("classes") ? parseGdClasses(root.getAsJsonArray("classes")) : Collections.emptyList();
            List<Singleton> singletons = root.has("singletons") ? parseSingletons(root.getAsJsonArray("singletons")) : Collections.emptyList();
            List<NativeStructure> nativeStructures = root.has("native_structures") ? parseNativeStructures(root.getAsJsonArray("native_structures")) : Collections.emptyList();

            return new ExtensionAPI(header, Collections.emptyList(), Collections.emptyList(), globalEnums, utilityFunctions, builtinClasses, classes, singletons, nativeStructures);
        }
    }

    private static ExtensionHeader parseHeader(JsonObject obj) {
        if (obj == null) return new ExtensionHeader(0,0,0, "", "", "", "");
        var versionMajor = obj.has("version_major") ? obj.get("version_major").getAsInt() : 0;
        var versionMinor = obj.has("version_minor") ? obj.get("version_minor").getAsInt() : 0;
        var versionPatch = obj.has("version_patch") ? obj.get("version_patch").getAsInt() : 0;
        var versionStatus = obj.has("version_status") ? obj.get("version_status").getAsString() : "";
        var versionBuild = obj.has("version_build") ? obj.get("version_build").getAsString() : "";
        var versionFullName = obj.has("version_full_name") ? obj.get("version_full_name").getAsString() : "";
        var precision = obj.has("precision") ? obj.get("precision").getAsString() : "";
        return new ExtensionHeader(versionMajor, versionMinor, versionPatch, versionStatus, versionBuild, versionFullName, precision);
    }

    private static List<GlobalEnum> parseGlobalEnums(JsonArray arr) {
        var out = new ArrayList<GlobalEnum>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isBitfield = o.has("is_bitfield") && o.get("is_bitfield").getAsBoolean();
            var values = new ArrayList<EnumValue>();
            if (o.has("values")) {
                for (JsonElement ve : o.getAsJsonArray("values")) {
                    var v = ve.getAsJsonObject();
                    var vn = v.has("name") ? v.get("name").getAsString() : null;
                    var vv = v.has("value") ? v.get("value").getAsInt() : 0;
                    values.add(new EnumValue(vn, vv));
                }
            }
            out.add(new GlobalEnum(name, isBitfield, Collections.unmodifiableList(values)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<UtilityFunction> parseUtilityFunctions(JsonArray arr) {
        var out = new ArrayList<UtilityFunction>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var returnType = o.has("return_type") ? o.get("return_type").getAsString() : null;
            var category = o.has("category") ? o.get("category").getAsString() : null;
            var isVararg = o.has("is_vararg") && o.get("is_vararg").getAsBoolean();
            var hash = o.has("hash") ? o.get("hash").getAsInt() : 0;
            var args = new ArrayList<FunctionArgument>();
            if (o.has("arguments")) {
                for (JsonElement ae : o.getAsJsonArray("arguments")) {
                    var a = ae.getAsJsonObject();
                    var an = a.has("name") ? a.get("name").getAsString() : null;
                    var at = a.has("type") ? a.get("type").getAsString() : null;
                    args.add(new FunctionArgument(an, at, a.has("default_value") ? a.get("default_value").getAsString() : null));
                }
            }
            out.add(new UtilityFunction(name, returnType, category, isVararg, hash, Collections.unmodifiableList(args)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<BuiltinClass> parseBuiltinClasses(JsonArray arr) {
        var out = new ArrayList<BuiltinClass>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isKeyed = o.has("is_keyed") && o.get("is_keyed").getAsBoolean();

            var operators = new ArrayList<BuiltinClass.ClassOperator>();
            if (o.has("operators")) {
                for (JsonElement oe : o.getAsJsonArray("operators")) {
                    var op = oe.getAsJsonObject();
                    operators.add(new BuiltinClass.ClassOperator(
                            op.has("name") ? op.get("name").getAsString() : null,
                            op.has("right_type") ? op.get("right_type").getAsString() : null,
                            op.has("return_type") ? op.get("return_type").getAsString() : null
                    ));
                }
            }

            var methods = new ArrayList<BuiltinClass.ClassMethod>();
            if (o.has("methods")) {
                for (JsonElement me : o.getAsJsonArray("methods")) {
                    var mo = me.getAsJsonObject();
                    var methodName = mo.has("name") ? mo.get("name").getAsString() : null;
                    var returnType = mo.has("return_type") ? mo.get("return_type").getAsString() : null;
                    var isVararg = mo.has("is_vararg") && mo.get("is_vararg").getAsBoolean();
                    var isConst = mo.has("is_const") && mo.get("is_const").getAsBoolean();
                    var isStatic = mo.has("is_static") && mo.get("is_static").getAsBoolean();
                    var isVirtual = mo.has("is_virtual") && mo.get("is_virtual").getAsBoolean();
                    var hash = mo.has("hash") ? mo.get("hash").getAsLong() : 0L;
                    var args = new ArrayList<FunctionArgument>();
                    if (mo.has("arguments")) {
                        for (JsonElement ae : mo.getAsJsonArray("arguments")) {
                            var a = ae.getAsJsonObject();
                            args.add(new FunctionArgument(
                                    a.has("name") ? a.get("name").getAsString() : null,
                                    a.has("type") ? a.get("type").getAsString() : null,
                                    a.has("default_value") ? a.get("default_value").getAsString() : null
                            ));
                        }
                    }
                    BuiltinClass.ClassMethod.ReturnValue rv = null;
                    if (mo.has("return_value")) {
                        var rvo = mo.getAsJsonObject("return_value");
                        rv = new BuiltinClass.ClassMethod.ReturnValue(rvo.has("type") ? rvo.get("type").getAsString() : null);
                    } else if (returnType != null) {
                        rv = new BuiltinClass.ClassMethod.ReturnValue(returnType);
                    }
                    methods.add(new BuiltinClass.ClassMethod(methodName, returnType, isVararg, isConst, isStatic, isVirtual, hash, Collections.unmodifiableList(args), null, rv));
                }
            }

            var enums = new ArrayList<BuiltinClass.ClassEnum>();
            if (o.has("enums")) {
                for (JsonElement ee : o.getAsJsonArray("enums")) {
                    var eo = ee.getAsJsonObject();
                    var ename = eo.has("name") ? eo.get("name").getAsString() : null;
                    var isBit = eo.has("is_bitfield") && eo.get("is_bitfield").getAsBoolean();
                    var vals = new ArrayList<EnumValue>();
                    if (eo.has("values")) {
                        for (JsonElement ve : eo.getAsJsonArray("values")) {
                            var v = ve.getAsJsonObject();
                            vals.add(new EnumValue(v.has("name") ? v.get("name").getAsString() : null, v.has("value") ? v.get("value").getAsInt() : 0));
                        }
                    }
                    enums.add(new BuiltinClass.ClassEnum(ename, isBit, Collections.unmodifiableList(vals)));
                }
            }

            var constructors = new ArrayList<BuiltinClass.ConstructorInfo>();
            if (o.has("constructors")) {
                for (JsonElement ce : o.getAsJsonArray("constructors")) {
                    var co = ce.getAsJsonObject();
                    var idx = co.has("index") ? co.get("index").getAsInt() : 0;
                    var carg = new ArrayList<FunctionArgument>();
                    if (co.has("arguments")) {
                        for (JsonElement ca : co.getAsJsonArray("arguments")) {
                            var a = ca.getAsJsonObject();
                            carg.add(new FunctionArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, a.has("default_value") ? a.get("default_value").getAsString() : null));
                        }
                    }
                    constructors.add(new BuiltinClass.ConstructorInfo(idx, Collections.unmodifiableList(carg)));
                }
            }

            var properties = new ArrayList<BuiltinClass.PropertyInfo>();
            if (o.has("properties")) {
                for (JsonElement pe : o.getAsJsonArray("properties")) {
                    var po = pe.getAsJsonObject();
                    properties.add(new BuiltinClass.PropertyInfo(
                            po.has("name") ? po.get("name").getAsString() : null,
                            po.has("type") ? po.get("type").getAsString() : null,
                            po.has("is_readable") && po.get("is_readable").getAsBoolean(),
                            po.has("is_writable") && po.get("is_writable").getAsBoolean(),
                            po.has("default_value") ? po.get("default_value").getAsString() : null
                    ));
                }
            }

            var constants = new ArrayList<BuiltinClass.ConstantInfo>();
            if (o.has("constants")) {
                for (JsonElement ce : o.getAsJsonArray("constants")) {
                    var co = ce.getAsJsonObject();
                    constants.add(new BuiltinClass.ConstantInfo(co.has("name") ? co.get("name").getAsString() : null, co.has("value") ? co.get("value").getAsString() : null));
                }
            }

            out.add(new BuiltinClass(name, isKeyed, Collections.unmodifiableList(operators), Collections.unmodifiableList(methods), Collections.unmodifiableList(enums), Collections.unmodifiableList(constructors), Collections.unmodifiableList(properties), Collections.unmodifiableList(constants)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<GdClass> parseGdClasses(JsonArray arr) {
        var out = new ArrayList<GdClass>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isRefcounted = o.has("is_refcounted") && o.get("is_refcounted").getAsBoolean();
            var isInstantiable = o.has("is_instantiable") && o.get("is_instantiable").getAsBoolean();
            var inherits = o.has("inherits") ? o.get("inherits").getAsString() : null;
            var apiType = o.has("api_type") ? o.get("api_type").getAsString() : null;

            var enums = new ArrayList<GdClass.ClassEnum>();
            if (o.has("enums")) {
                for (JsonElement ee : o.getAsJsonArray("enums")) {
                    var eo = ee.getAsJsonObject();
                    var ename = eo.has("name") ? eo.get("name").getAsString() : null;
                    var isBit = eo.has("is_bitfield") && eo.get("is_bitfield").getAsBoolean();
                    var vals = new ArrayList<EnumValue>();
                    if (eo.has("values")) {
                        for (JsonElement ve : eo.getAsJsonArray("values")) {
                            var v = ve.getAsJsonObject();
                            vals.add(new EnumValue(v.has("name") ? v.get("name").getAsString() : null, v.has("value") ? v.get("value").getAsInt() : 0));
                        }
                    }
                    enums.add(new GdClass.ClassEnum(ename, isBit, Collections.unmodifiableList(vals)));
                }
            }

            var methods = new ArrayList<GdClass.ClassMethod>();
            if (o.has("methods")) {
                for (JsonElement me : o.getAsJsonArray("methods")) {
                    var mo = me.getAsJsonObject();
                    var methodName = mo.has("name") ? mo.get("name").getAsString() : null;
                    var isConst = mo.has("is_const") && mo.get("is_const").getAsBoolean();
                    var isVararg = mo.has("is_vararg") && mo.get("is_vararg").getAsBoolean();
                    var isStatic = mo.has("is_static") && mo.get("is_static").getAsBoolean();
                    var isVirtual = mo.has("is_virtual") && mo.get("is_virtual").getAsBoolean();
                    var hash = mo.has("hash") ? mo.get("hash").getAsLong() : 0L;
                    var hc = new ArrayList<Long>();
                    if (mo.has("hash_compatibility")) {
                        for (JsonElement he : mo.getAsJsonArray("hash_compatibility")) hc.add(he.getAsLong());
                    }
                    var args = new ArrayList<FunctionArgument>();
                    if (mo.has("arguments")) {
                        for (JsonElement ae : mo.getAsJsonArray("arguments")) {
                            var a = ae.getAsJsonObject();
                            args.add(new FunctionArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, a.has("default_value") ? a.get("default_value").getAsString() : null));
                        }
                    }
                    GdClass.ClassMethod.ClassMethodReturn rv = null;
                    if (mo.has("return_value")) {
                        var rvo = mo.getAsJsonObject("return_value");
                        rv = new GdClass.ClassMethod.ClassMethodReturn(rvo.has("type") ? rvo.get("type").getAsString() : null);
                    }
                    methods.add(new GdClass.ClassMethod(methodName, isConst, isVararg, isStatic, isVirtual, hash, Collections.unmodifiableList(hc), rv, Collections.unmodifiableList(args)));
                }
            }

            var constructors = new ArrayList<GdClass.ConstructorInfo>();
            if (o.has("constructors")) {
                for (JsonElement ce : o.getAsJsonArray("constructors")) {
                    var co = ce.getAsJsonObject();
                    var idx = co.has("index") ? co.get("index").getAsInt() : 0;
                    var carg = new ArrayList<FunctionArgument>();
                    if (co.has("arguments")) {
                        for (JsonElement ca : co.getAsJsonArray("arguments")) {
                            var a = ca.getAsJsonObject();
                            carg.add(new FunctionArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, a.has("default_value") ? a.get("default_value").getAsString() : null));
                        }
                    }
                    constructors.add(new GdClass.ConstructorInfo(idx, Collections.unmodifiableList(carg)));
                }
            }

            var properties = new ArrayList<GdClass.PropertyInfo>();
            if (o.has("properties")) {
                for (JsonElement pe : o.getAsJsonArray("properties")) {
                    var po = pe.getAsJsonObject();
                    properties.add(new GdClass.PropertyInfo(
                            po.has("name") ? po.get("name").getAsString() : null,
                            po.has("type") ? po.get("type").getAsString() : null,
                            po.has("is_readable") && po.get("is_readable").getAsBoolean(),
                            po.has("is_writable") && po.get("is_writable").getAsBoolean(),
                            po.has("default_value") ? po.get("default_value").getAsString() : null
                    ));
                }
            }

            var constants = new ArrayList<GdClass.ConstantInfo>();
            if (o.has("constants")) {
                for (JsonElement ce : o.getAsJsonArray("constants")) {
                    var co = ce.getAsJsonObject();
                    constants.add(new GdClass.ConstantInfo(co.has("name") ? co.get("name").getAsString() : null, co.has("value") ? co.get("value").getAsString() : null));
                }
            }

            var hasDestructor = o.has("has_destructor") && o.get("has_destructor").getAsBoolean();

            out.add(new GdClass(name, isRefcounted, isInstantiable, inherits, apiType, Collections.unmodifiableList(enums), Collections.unmodifiableList(methods), Collections.unmodifiableList(properties), Collections.unmodifiableList(constants)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Singleton> parseSingletons(JsonArray arr) {
        var out = new ArrayList<Singleton>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            out.add(new Singleton(o.has("name") ? o.get("name").getAsString() : null, o.has("type") ? o.get("type").getAsString() : null));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<NativeStructure> parseNativeStructures(JsonArray arr) {
        var out = new ArrayList<NativeStructure>();
        for (JsonElement el : arr) {
            var o = el.getAsJsonObject();
            out.add(new NativeStructure(o.has("name") ? o.get("name").getAsString() : null, o.has("format") ? o.get("format").getAsString() : null));
        }
        return Collections.unmodifiableList(out);
    }

    /// Serialize the given ExtensionAPI instance to JSON using the same naming policy.
    public static @NotNull String toJson(@NotNull ExtensionAPI api) {
        return GSON.toJson(api);
    }
}
