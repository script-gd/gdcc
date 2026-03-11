package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.resolver.ScopeTypeParsers;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityDefaultLiteralMaterializationTest {
    private static final String DOCUMENTED_DEFAULT_VALUES = """
            Transform2D(1, 0, 0, 1, 0, 0), RID(), -99, "0000000000000000000000000000000000000000000000000000000000000000", Color(0, 0, 0, 0), PackedVector2Array(), 0.08, "20340101000000", "Alert!", PackedVector3Array(), 20.0, 90, ",", "20140101000000", 50, 32767, -1, 15, 16, "application/octet-stream", PackedColorArray(), 65536, PackedFloat32Array(), 2000, 65535, 4294967295, 163, 120, 0, 1, 2, 3, 1.0, Vector2i(0, 0), null, 4, 400, 5, PackedInt64Array(), 1024, 6, 5.0, true, Callable(), "", Vector2(1, 1), Array[StringName]([]), "UDP", &"Master", Array[Plane]([]), Array[RID]([]), 8192, 1000, 0.001, 0.75, Vector2(0, -1), PackedByteArray(), Vector2(0, 0), "CN=myserver,O=myorganisation,C=IT", PackedStringArray(), "*", 30, Array[Array]([]), Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0), 32, PackedInt32Array(), Color(0, 0, 0, 1), Vector3(0, 1, 0), [], {}, Rect2i(0, 0, 0, 0), Vector2i(-1, -1), Vector2i(1, 1), &"", "•", "endregion", false, "region", 0.01, Array[RDPipelineSpecializationConstant]([]), "None", 264, 100, 0.0, Color(1, 1, 1, 1), 0.1, -1.0, Vector3(0, 0, 0), "InternetGatewayDevice", 0.2, 2.0, 500, Rect2(0, 0, 0, 0), 4.0, 0.8, NodePath("")
            """;

    @Test
    @DisplayName("materializeUtilityDefaultValue should cover all documented default literals")
    void shouldMaterializeAllDocumentedDefaults() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        var samplesByLiteral = buildSamplesByLiteral(api, classRegistry);
        var documentedLiterals = parseDocumentedDefaultLiterals();

        var missingLiterals = new ArrayList<String>();
        for (var literal : documentedLiterals) {
            if (!samplesByLiteral.containsKey(literal)) {
                missingLiterals.add(literal);
            }
        }
        assertTrue(missingLiterals.isEmpty(),
                () -> "Documented defaults not found in extension_api_451.json: " + String.join(", ", missingLiterals));

        for (var selectedLiteral : documentedLiterals) {
            var sample = selectPreferredSample(selectedLiteral, samplesByLiteral.get(selectedLiteral));
            if (scoreSample(selectedLiteral, sample) < 50) {
                sample = syntheticSampleForLiteral(selectedLiteral, classRegistry);
            }
            var selectedSample = sample;
            assertDoesNotThrow(
                    () -> materializeWithSample(api, selectedSample),
                    () -> "Failed to materialize default literal '" + selectedLiteral + "' from " + selectedSample.source()
            );
        }
    }

    @Test
    @DisplayName("materializeUtilityDefaultValue should support typed array defaults resolved from registry-backed metadata")
    void shouldMaterializeTypedArrayDefaultResolvedFromRegistryBackedMetadata() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        var sample = new DefaultSample(
                ScopeTypeParsers.parseExtensionTypeMetadata(
                        "typedarray::RDPipelineSpecializationConstant",
                        "test typedarray default metadata",
                        classRegistry
                ),
                "Array[RDPipelineSpecializationConstant]([])",
                "synthetic:typedarray_metadata"
        );

        assertDoesNotThrow(() -> materializeWithSample(api, sample));
    }

    private void materializeWithSample(ExtensionAPI api, DefaultSample sample) {
        var builder = newBodyBuilder(api);
        var temp = builder.newTempVariable("default_literal", sample.type());
        builder.declareTempVar(temp);
        builder.helper().builtinBuilder().materializeUtilityDefaultValue(
                builder,
                temp,
                sample.literal(),
                sample.source(),
                1
        );
        builder.destroyTempVar(temp);
    }

    private CBodyBuilder newBodyBuilder(ExtensionAPI api) {
        var projectInfo = new ProjectInfo("DefaultLiteralCoverage", GodotVersion.V451, Path.of(".")) {
        };
        var classRegistry = new ClassRegistry(api);
        var context = new CodegenContext(projectInfo, classRegistry);

        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("materialize_default_literal");
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");

        var helper = new CGenHelper(context, List.of(clazz));
        var bodyBuilder = new CBodyBuilder(helper, clazz, func);
        bodyBuilder.setCurrentPosition(entry, 0, new CallGlobalInsn(null, "print", List.of()));
        return bodyBuilder;
    }

    private Map<String, List<DefaultSample>> buildSamplesByLiteral(ExtensionAPI api,
                                                                   ClassRegistry classRegistry) {
        var samplesByLiteral = new LinkedHashMap<String, List<DefaultSample>>();
        for (var utilityFunction : api.utilityFunctions()) {
            collectFromArguments(
                    samplesByLiteral,
                    utilityFunction.arguments(),
                    "utility:" + utilityFunction.name(),
                    classRegistry
            );
        }
        for (var builtinClass : api.builtinClasses()) {
            for (var method : builtinClass.methods()) {
                collectFromArguments(
                        samplesByLiteral,
                        method.arguments(),
                        "builtin_method:" + builtinClass.name() + ":" + method.name(),
                        classRegistry
                );
            }
            for (var ctor : builtinClass.constructors()) {
                collectFromArguments(
                        samplesByLiteral,
                        ctor.arguments(),
                        "builtin_ctor:" + builtinClass.name() + ":new",
                        classRegistry
                );
            }
        }
        for (var gdClass : api.classes()) {
            for (var method : gdClass.methods()) {
                collectFromArguments(
                        samplesByLiteral,
                        method.arguments(),
                        "class_method:" + gdClass.name() + ":" + method.name(),
                        classRegistry
                );
            }
        }
        return samplesByLiteral;
    }

    private void collectFromArguments(Map<String, List<DefaultSample>> out,
                                      List<ExtensionFunctionArgument> arguments,
                                      String sourcePrefix,
                                      ClassRegistry classRegistry) {
        for (var i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.defaultValue() == null || argument.type() == null) {
                continue;
            }
            var parsedType = ScopeTypeParsers.parseExtensionTypeMetadata(
                    argument.type(),
                    sourcePrefix + "#" + (i + 1),
                    classRegistry
            );
            out.computeIfAbsent(argument.defaultValue(), ignored -> new ArrayList<>())
                    .add(new DefaultSample(parsedType, argument.defaultValue(), sourcePrefix + "#" + (i + 1)));
        }
    }

    private DefaultSample selectPreferredSample(String literal, List<DefaultSample> samples) {
        var preferred = samples.getFirst();
        var preferredScore = scoreSample(literal, preferred);
        for (var i = 1; i < samples.size(); i++) {
            var sample = samples.get(i);
            var score = scoreSample(literal, sample);
            if (score > preferredScore) {
                preferred = sample;
                preferredScore = score;
            }
        }
        return preferred;
    }

    private int scoreSample(String literal, DefaultSample sample) {
        var type = sample.type();
        if ("null".equals(literal)) {
            return 100;
        }
        if (type instanceof GdBoolType || type instanceof GdIntType || type instanceof GdFloatType ||
                type instanceof GdStringType || type instanceof GdStringNameType || type instanceof GdNodePathType ||
                type instanceof GdArrayType || type instanceof GdDictionaryType || type instanceof GdVariantType) {
            return 90;
        }
        if (type instanceof GdObjectType) {
            return 10;
        }
        return 60;
    }

    private DefaultSample syntheticSampleForLiteral(String literal, ClassRegistry classRegistry) {
        var trimmed = literal.trim();
        if ("null".equals(trimmed)) {
            return new DefaultSample(GdVariantType.VARIANT, trimmed, "synthetic:null");
        }
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return new DefaultSample(GdBoolType.BOOL, trimmed, "synthetic:bool");
        }
        if (trimmed.matches("[+-]?\\d+")) {
            return new DefaultSample(GdIntType.INT, trimmed, "synthetic:int");
        }
        if (trimmed.matches("[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) {
            return new DefaultSample(GdFloatType.FLOAT, trimmed, "synthetic:float");
        }
        if (trimmed.startsWith("&\"") && trimmed.endsWith("\"")) {
            return new DefaultSample(GdStringNameType.STRING_NAME, trimmed, "synthetic:string_name");
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return new DefaultSample(GdStringType.STRING, trimmed, "synthetic:string");
        }
        if ("[]".equals(trimmed)) {
            return new DefaultSample(new GdArrayType(GdVariantType.VARIANT), trimmed, "synthetic:array");
        }
        if ("{}".equals(trimmed)) {
            return new DefaultSample(
                    new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                    trimmed,
                    "synthetic:dictionary"
            );
        }
        var leftParen = trimmed.indexOf('(');
        if (leftParen > 0 && trimmed.endsWith(")")) {
            var ctorType = classRegistry.tryResolveDeclaredType(trimmed.substring(0, leftParen).trim());
            if (ctorType != null) {
                return new DefaultSample(ctorType, trimmed, "synthetic:constructor");
            }
        }
        return new DefaultSample(GdVariantType.VARIANT, trimmed, "synthetic:variant");
    }

    private List<String> parseDocumentedDefaultLiterals() {
        return splitTopLevelCommaSeparated(DOCUMENTED_DEFAULT_VALUES.trim());
    }

    private List<String> splitTopLevelCommaSeparated(String source) {
        if (source.isBlank()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        var parenDepth = 0;
        var bracketDepth = 0;
        var inString = false;
        var escaped = false;
        for (var i = 0; i < source.length(); i++) {
            var ch = source.charAt(i);
            if (inString) {
                current.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    inString = true;
                    current.append(ch);
                }
                case '(' -> {
                    parenDepth++;
                    current.append(ch);
                }
                case ')' -> {
                    parenDepth--;
                    current.append(ch);
                }
                case '[' -> {
                    bracketDepth++;
                    current.append(ch);
                }
                case ']' -> {
                    bracketDepth--;
                    current.append(ch);
                }
                case ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0) {
                        var token = current.toString().trim();
                        if (!token.isEmpty()) {
                            result.add(token);
                        }
                        current.setLength(0);
                    } else {
                        current.append(ch);
                    }
                }
                default -> current.append(ch);
            }
        }
        var tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private record DefaultSample(GdType type, String literal, String source) {
    }
}
