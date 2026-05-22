package gd.script.gdcc.gdextension;

import com.google.gson.*;
import gd.script.gdcc.enums.GodotVersion;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/// Utility to load and serialize the extension_api_*.json as an ExtensionAPI instance.
public final class ExtensionApiLoader {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    // Skip any fields from the scope package and skip the internal back-reference 'definedIn'
                    if ("definedIn".equals(f.getName())) return true;
                    return f.getDeclaredType().getTypeName().startsWith("gd.script.gdcc.scope");
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return clazz.getName().startsWith("gd.script.gdcc.scope");
                }
            })
            .create();

    private ExtensionApiLoader() {
    }

    private static ExtensionAPI instance;
    private static final ConcurrentHashMap<GodotVersion, ExtensionAPI> versionedInstances = new ConcurrentHashMap<>();

    /// Load the extension API from the classpath resource.
    public static @NotNull ExtensionAPI loadDefault() throws IOException {
        if (instance != null) {
            return instance;
        }
        instance = loadFromResource("/extension_api_451.json");
        return instance;
    }

    public static @NotNull ExtensionAPI loadVersion(@NotNull GodotVersion version) throws IOException {
        if (versionedInstances.containsKey(version)) {
            return versionedInstances.get(version);
        }
        var resourcePath = "/extension_api_" + version.getShortVersion() + ".json";
        var api = loadFromResource(resourcePath);
        versionedInstances.put(version, api);
        return api;
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

            List<ExtensionBuiltinClassSizes> builtinClassSizes = root.has("builtin_class_sizes") ? parseBuiltinClassSizes(root.getAsJsonArray("builtin_class_sizes")) : Collections.emptyList();
            List<ExtensionBuiltinClassMemberOffsets> builtinClassMemberOffsets = root.has("builtin_class_member_offsets") ? parseBuiltinClassMemberOffsets(root.getAsJsonArray("builtin_class_member_offsets")) : Collections.emptyList();
            List<ExtensionGlobalConstant> globalConstants = root.has("global_constants") ? parseGlobalConstants(root.getAsJsonArray("global_constants")) : Collections.emptyList();
            List<ExtensionGlobalEnum> globalEnums = root.has("global_enums") ? parseGlobalEnums(root.getAsJsonArray("global_enums")) : Collections.emptyList();
            List<ExtensionUtilityFunction> utilityFunctions = root.has("utility_functions") ? parseUtilityFunctions(root.getAsJsonArray("utility_functions")) : Collections.emptyList();
            List<ExtensionBuiltinClass> builtinClasses = root.has("builtin_classes") ? parseBuiltinClasses(root.getAsJsonArray("builtin_classes")) : Collections.emptyList();
            List<ExtensionGdClass> classes = root.has("classes") ? parseGdClasses(root.getAsJsonArray("classes")) : Collections.emptyList();
            List<ExtensionSingleton> singletons = root.has("singletons") ? parseSingletons(root.getAsJsonArray("singletons")) : Collections.emptyList();
            List<ExtensionNativeStructure> nativeStructures = root.has("native_structures") ? parseNativeStructures(root.getAsJsonArray("native_structures")) : Collections.emptyList();

            return new ExtensionAPI(header, builtinClassSizes, builtinClassMemberOffsets, globalConstants, globalEnums, utilityFunctions, builtinClasses, classes, singletons, nativeStructures);
        }
    }

    private static ExtensionHeader parseHeader(JsonObject obj) {
        if (obj == null) return new ExtensionHeader(0, 0, 0, "", "", "", "");
        var versionMajor = obj.has("version_major") ? obj.get("version_major").getAsInt() : 0;
        var versionMinor = obj.has("version_minor") ? obj.get("version_minor").getAsInt() : 0;
        var versionPatch = obj.has("version_patch") ? obj.get("version_patch").getAsInt() : 0;
        var versionStatus = obj.has("version_status") ? obj.get("version_status").getAsString() : "";
        var versionBuild = obj.has("version_build") ? obj.get("version_build").getAsString() : "";
        var versionFullName = obj.has("version_full_name") ? obj.get("version_full_name").getAsString() : "";
        var precision = obj.has("precision") ? obj.get("precision").getAsString() : "";
        return new ExtensionHeader(versionMajor, versionMinor, versionPatch, versionStatus, versionBuild, versionFullName, precision);
    }

    private static List<ExtensionBuiltinClassSizes> parseBuiltinClassSizes(JsonArray arr) {
        var out = new ArrayList<ExtensionBuiltinClassSizes>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var sizes = new ArrayList<ExtensionBuiltinClassSizes.ClassSizeInfo>();
            if (o.has("sizes")) {
                for (var se : o.getAsJsonArray("sizes")) {
                    var s = se.getAsJsonObject();
                    sizes.add(new ExtensionBuiltinClassSizes.ClassSizeInfo(
                            s.get("name").getAsString(),
                            s.get("size").getAsInt()
                    ));
                }
            }
            out.add(new ExtensionBuiltinClassSizes(
                    o.get("build_configuration").getAsString(),
                    Collections.unmodifiableList(sizes)
            ));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionBuiltinClassMemberOffsets> parseBuiltinClassMemberOffsets(JsonArray arr) {
        var out = new ArrayList<ExtensionBuiltinClassMemberOffsets>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var classes = new ArrayList<ExtensionBuiltinClassMemberOffsets.ClassMemberData>();
            if (o.has("classes")) {
                for (var ce : o.getAsJsonArray("classes")) {
                    var c = ce.getAsJsonObject();
                    var members = new ArrayList<ExtensionBuiltinClassMemberOffsets.MemberOffsetData>();
                    if (c.has("members")) {
                        for (var me : c.getAsJsonArray("members")) {
                            var m = me.getAsJsonObject();
                            members.add(new ExtensionBuiltinClassMemberOffsets.MemberOffsetData(
                                    m.get("member").getAsString(),
                                    m.get("offset").getAsInt(),
                                    m.get("meta").getAsString()
                            ));
                        }
                    }
                    classes.add(new ExtensionBuiltinClassMemberOffsets.ClassMemberData(
                            c.get("name").getAsString(),
                            Collections.unmodifiableList(members)
                    ));
                }
            }
            out.add(new ExtensionBuiltinClassMemberOffsets(
                    o.get("build_configuration").getAsString(),
                    Collections.unmodifiableList(classes)
            ));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionGlobalConstant> parseGlobalConstants(JsonArray arr) {
        var out = new ArrayList<ExtensionGlobalConstant>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            out.add(new ExtensionGlobalConstant(
                    o.get("name").getAsString(),
                    o.get("value").getAsLong(),
                    o.has("is_bitfield") && o.get("is_bitfield").getAsBoolean()
            ));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionGlobalEnum> parseGlobalEnums(JsonArray arr) {
        var out = new ArrayList<ExtensionGlobalEnum>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isBitfield = o.has("is_bitfield") && o.get("is_bitfield").getAsBoolean();
            var values = new ArrayList<ExtensionEnumValue>();
            if (o.has("values")) {
                for (var ve : o.getAsJsonArray("values")) {
                    var v = ve.getAsJsonObject();
                    var vn = v.has("name") ? v.get("name").getAsString() : null;
                    var vv = v.has("value") ? v.get("value").getAsLong() : 0L;
                    values.add(new ExtensionEnumValue(vn, vv));
                }
            }
            out.add(new ExtensionGlobalEnum(name, isBitfield, Collections.unmodifiableList(values)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionUtilityFunction> parseUtilityFunctions(JsonArray arr) {
        var out = new ArrayList<ExtensionUtilityFunction>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var returnType = o.has("return_type") ? o.get("return_type").getAsString() : null;
            var category = o.has("category") ? o.get("category").getAsString() : null;
            var isVararg = o.has("is_vararg") && o.get("is_vararg").getAsBoolean();
            var hash = o.has("hash") ? o.get("hash").getAsInt() : 0;
            var args = new ArrayList<ExtensionFunctionArgument>();
            var func = new ExtensionUtilityFunction(name, returnType, category, isVararg, hash, Collections.unmodifiableList(args));
            if (o.has("arguments")) {
                for (var ae : o.getAsJsonArray("arguments")) {
                    var a = ae.getAsJsonObject();
                    var an = a.has("name") ? a.get("name").getAsString() : null;
                    var at = a.has("type") ? a.get("type").getAsString() : null;
                    args.add(new ExtensionFunctionArgument(an, at, a.has("default_value") ? a.get("default_value").getAsString() : null, func));
                }
            }
            out.add(func);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionBuiltinClass> parseBuiltinClasses(JsonArray arr) {
        var out = new ArrayList<ExtensionBuiltinClass>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isKeyed = o.has("is_keyed") && o.get("is_keyed").getAsBoolean();
            var hasDestructor = o.has("has_destructor") && o.get("has_destructor").getAsBoolean();
            var indexingReturnType = o.has("indexing_return_type") ? o.get("indexing_return_type").getAsString() : null;

            var operators = new ArrayList<ExtensionBuiltinClass.ClassOperator>();
            if (o.has("operators")) {
                for (var oe : o.getAsJsonArray("operators")) {
                    var op = oe.getAsJsonObject();
                    operators.add(new ExtensionBuiltinClass.ClassOperator(
                            op.has("name") ? op.get("name").getAsString() : null,
                            op.has("right_type") ? op.get("right_type").getAsString() : "",
                            op.has("return_type") ? op.get("return_type").getAsString() : null
                    ));
                }
            }

            var methods = new ArrayList<ExtensionBuiltinClass.ClassMethod>();
            if (o.has("methods")) {
                for (var me : o.getAsJsonArray("methods")) {
                    var mo = me.getAsJsonObject();
                    var methodName = mo.has("name") ? mo.get("name").getAsString() : null;
                    var returnType = mo.has("return_type") ? mo.get("return_type").getAsString() : null;
                    var isVararg = mo.has("is_vararg") && mo.get("is_vararg").getAsBoolean();
                    var isConst = mo.has("is_const") && mo.get("is_const").getAsBoolean();
                    var isStatic = mo.has("is_static") && mo.get("is_static").getAsBoolean();
                    var isVirtual = mo.has("is_virtual") && mo.get("is_virtual").getAsBoolean();
                    var hash = mo.has("hash") ? mo.get("hash").getAsLong() : 0L;
                    ExtensionBuiltinClass.ClassMethod.ReturnValue rv = null;
                    if (mo.has("return_value")) {
                        var rvo = mo.getAsJsonObject("return_value");
                        rv = new ExtensionBuiltinClass.ClassMethod.ReturnValue(rvo.has("type") ? rvo.get("type").getAsString() : null);
                    } else if (returnType != null) {
                        rv = new ExtensionBuiltinClass.ClassMethod.ReturnValue(returnType);
                    }
                    var args = new ArrayList<ExtensionFunctionArgument>();
                    var method = new ExtensionBuiltinClass.ClassMethod(methodName, returnType, isVararg, isConst, isStatic, isVirtual, hash, Collections.unmodifiableList(args), null, rv);
                    if (mo.has("arguments")) {
                        for (var ae : mo.getAsJsonArray("arguments")) {
                            var a = ae.getAsJsonObject();
                            args.add(new ExtensionFunctionArgument(
                                    a.has("name") ? a.get("name").getAsString() : null,
                                    a.has("type") ? a.get("type").getAsString() : null,
                                    a.has("default_value") ? a.get("default_value").getAsString() : null,
                                    method
                            ));
                        }
                    }
                    methods.add(method);
                }
            }

            var enums = new ArrayList<ExtensionBuiltinClass.ClassEnum>();
            if (o.has("enums")) {
                for (var ee : o.getAsJsonArray("enums")) {
                    var eo = ee.getAsJsonObject();
                    var ename = eo.has("name") ? eo.get("name").getAsString() : null;
                    var isBit = eo.has("is_bitfield") && eo.get("is_bitfield").getAsBoolean();
                    var vals = new ArrayList<ExtensionEnumValue>();
                    if (eo.has("values")) {
                        for (var ve : eo.getAsJsonArray("values")) {
                            var v = ve.getAsJsonObject();
                            vals.add(new ExtensionEnumValue(
                                    v.has("name") ? v.get("name").getAsString() : null,
                                    v.has("value") ? v.get("value").getAsLong() : 0L
                            ));
                        }
                    }
                    enums.add(new ExtensionBuiltinClass.ClassEnum(ename, isBit, Collections.unmodifiableList(vals)));
                }
            }

            var constructors = new ArrayList<ExtensionBuiltinClass.ConstructorInfo>();
            if (o.has("constructors")) {
                for (var ce : o.getAsJsonArray("constructors")) {
                    var co = ce.getAsJsonObject();
                    var idx = co.has("index") ? co.get("index").getAsInt() : 0;
                    var carg = new ArrayList<ExtensionFunctionArgument>();
                    var cons = new ExtensionBuiltinClass.ConstructorInfo(name, idx, Collections.unmodifiableList(carg));
                    if (co.has("arguments")) {
                        for (var ca : co.getAsJsonArray("arguments")) {
                            var a = ca.getAsJsonObject();
                            carg.add(new ExtensionFunctionArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, a.has("default_value") ? a.get("default_value").getAsString() : null, cons));
                        }
                    }
                    constructors.add(cons);
                }
            }

            // Builtin value types expose fields like `Vector3.x` only via the raw JSON `members` array.
            var members = new ArrayList<ExtensionBuiltinClass.MemberInfo>();
            if (o.has("members")) {
                for (var me : o.getAsJsonArray("members")) {
                    var mo = me.getAsJsonObject();
                    members.add(new ExtensionBuiltinClass.MemberInfo(
                            mo.has("name") ? mo.get("name").getAsString() : null,
                            mo.has("type") ? mo.get("type").getAsString() : null
                    ));
                }
            }

            var constants = new ArrayList<ExtensionBuiltinClass.ConstantInfo>();
            if (o.has("constants")) {
                for (var ce : o.getAsJsonArray("constants")) {
                    var co = ce.getAsJsonObject();
                    constants.add(new ExtensionBuiltinClass.ConstantInfo(
                            co.has("name") ? co.get("name").getAsString() : null,
                            co.has("type") ? co.get("type").getAsString() : null,
                            co.has("value") ? co.get("value").getAsString() : null
                    ));
                }
            }

            out.add(new ExtensionBuiltinClass(
                    name,
                    isKeyed,
                    hasDestructor,
                    indexingReturnType,
                    Collections.unmodifiableList(operators),
                    Collections.unmodifiableList(methods),
                    Collections.unmodifiableList(enums),
                    Collections.unmodifiableList(constructors),
                    Collections.unmodifiableList(members),
                    Collections.unmodifiableList(constants)
            ));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionGdClass> parseGdClasses(JsonArray arr) {
        var out = new ArrayList<ExtensionGdClass>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            var name = o.has("name") ? o.get("name").getAsString() : null;
            var isRefcounted = o.has("is_refcounted") && o.get("is_refcounted").getAsBoolean();
            var isInstantiable = o.has("is_instantiable") && o.get("is_instantiable").getAsBoolean();
            var inherits = o.has("inherits") ? o.get("inherits").getAsString() : null;
            var apiType = o.has("api_type") ? o.get("api_type").getAsString() : null;

            var enums = new ArrayList<ExtensionGdClass.ClassEnum>();
            if (o.has("enums")) {
                for (var ee : o.getAsJsonArray("enums")) {
                    var eo = ee.getAsJsonObject();
                    var ename = eo.has("name") ? eo.get("name").getAsString() : null;
                    var isBit = eo.has("is_bitfield") && eo.get("is_bitfield").getAsBoolean();
                    var vals = new ArrayList<ExtensionEnumValue>();
                    if (eo.has("values")) {
                        for (var ve : eo.getAsJsonArray("values")) {
                            var v = ve.getAsJsonObject();
                            vals.add(new ExtensionEnumValue(
                                    v.has("name") ? v.get("name").getAsString() : null,
                                    v.has("value") ? v.get("value").getAsLong() : 0L
                            ));
                        }
                    }
                    enums.add(new ExtensionGdClass.ClassEnum(ename, isBit, Collections.unmodifiableList(vals)));
                }
            }

            var methods = new ArrayList<ExtensionGdClass.ClassMethod>();
            if (o.has("methods")) {
                for (var me : o.getAsJsonArray("methods")) {
                    var mo = me.getAsJsonObject();
                    var methodName = mo.has("name") ? mo.get("name").getAsString() : null;
                    var isConst = mo.has("is_const") && mo.get("is_const").getAsBoolean();
                    var isVararg = mo.has("is_vararg") && mo.get("is_vararg").getAsBoolean();
                    var isStatic = mo.has("is_static") && mo.get("is_static").getAsBoolean();
                    var isVirtual = mo.has("is_virtual") && mo.get("is_virtual").getAsBoolean();
                    var hash = mo.has("hash") ? mo.get("hash").getAsLong() : 0L;
                    var hc = new ArrayList<Long>();
                    if (mo.has("hash_compatibility")) {
                        for (var he : mo.getAsJsonArray("hash_compatibility")) hc.add(he.getAsLong());
                    }
                    ExtensionGdClass.ClassMethod.ClassMethodReturn rv = null;
                    if (mo.has("return_value")) {
                        var rvo = mo.getAsJsonObject("return_value");
                        rv = new ExtensionGdClass.ClassMethod.ClassMethodReturn(rvo.has("type") ? rvo.get("type").getAsString() : null);
                    }
                    var args = new ArrayList<ExtensionFunctionArgument>();
                    var method = new ExtensionGdClass.ClassMethod(methodName, isConst, isVararg, isStatic, isVirtual, hash, Collections.unmodifiableList(hc), rv, Collections.unmodifiableList(args));
                    if (mo.has("arguments")) {
                        for (var ae : mo.getAsJsonArray("arguments")) {
                            var a = ae.getAsJsonObject();
                            args.add(new ExtensionFunctionArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, a.has("default_value") ? a.get("default_value").getAsString() : null, method));
                        }
                    }
                    methods.add(method);
                }
            }

            // parse signals
            var signals = new ArrayList<ExtensionGdClass.SignalInfo>();
            if (o.has("signals")) {
                for (var se : o.getAsJsonArray("signals")) {
                    var so = se.getAsJsonObject();
                    var sname = so.has("name") ? so.get("name").getAsString() : null;
                    var sargs = new ArrayList<ExtensionGdClass.SignalInfo.SignalArgument>();
                    var sinfo = new ExtensionGdClass.SignalInfo(sname, Collections.unmodifiableList(sargs));
                    if (so.has("arguments")) {
                        for (var ae : so.getAsJsonArray("arguments")) {
                            var a = ae.getAsJsonObject();
                            sargs.add(new ExtensionGdClass.SignalInfo.SignalArgument(a.has("name") ? a.get("name").getAsString() : null, a.has("type") ? a.get("type").getAsString() : null, sinfo));
                        }
                    }
                    signals.add(sinfo);
                }
            }

            var properties = new ArrayList<ExtensionGdClass.PropertyInfo>();
            if (o.has("properties")) {
                for (var pe : o.getAsJsonArray("properties")) {
                    var po = pe.getAsJsonObject();
                    var hasReadableField = po.has("is_readable");
                    var hasWritableField = po.has("is_writable");
                    var getter = po.has("getter") ? po.get("getter").getAsString() : null;
                    var setter = po.has("setter") ? po.get("setter").getAsString() : null;
                    var isReadable = hasReadableField ? po.get("is_readable").getAsBoolean() : getter != null;
                    var isWritable = hasWritableField ? po.get("is_writable").getAsBoolean() : setter != null;
                    properties.add(new ExtensionGdClass.PropertyInfo(
                            po.has("name") ? po.get("name").getAsString() : null,
                            po.has("type") ? po.get("type").getAsString() : null,
                            isReadable,
                            isWritable,
                            po.has("default_value") ? po.get("default_value").getAsString() : null,
                            getter,
                            setter,
                            po.has("index") ? po.get("index").getAsInt() : null
                    ));
                }
            }

            var constants = new ArrayList<ExtensionGdClass.ConstantInfo>();
            if (o.has("constants")) {
                for (var ce : o.getAsJsonArray("constants")) {
                    var co = ce.getAsJsonObject();
                    constants.add(new ExtensionGdClass.ConstantInfo(co.has("name") ? co.get("name").getAsString() : null, co.has("value") ? co.get("value").getAsString() : null));
                }
            }

            out.add(new ExtensionGdClass(name, isRefcounted, isInstantiable, inherits, apiType, Collections.unmodifiableList(enums), Collections.unmodifiableList(methods), Collections.unmodifiableList(signals), Collections.unmodifiableList(properties), Collections.unmodifiableList(constants)));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionSingleton> parseSingletons(JsonArray arr) {
        var out = new ArrayList<ExtensionSingleton>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            out.add(new ExtensionSingleton(o.has("name") ? o.get("name").getAsString() : null, o.has("type") ? o.get("type").getAsString() : null));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ExtensionNativeStructure> parseNativeStructures(JsonArray arr) {
        var out = new ArrayList<ExtensionNativeStructure>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            out.add(new ExtensionNativeStructure(o.has("name") ? o.get("name").getAsString() : null, o.has("format") ? o.get("format").getAsString() : null));
        }
        return Collections.unmodifiableList(out);
    }

    /// Serialize the given ExtensionAPI instance to JSON using the same naming policy.
    public static @NotNull String toJson(@NotNull ExtensionAPI api) {
        return GSON.toJson(api);
    }
}
