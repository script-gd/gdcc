package gd.script.gdcc.gdextension;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.resolver.ScopeTypeParsers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the union-type NCA mapping feature: the bundled dump's 33 comma-joined property
/// type strings all map to their nearest common ancestor (or first element for the
/// base-with-exclusions family), engine property `getType()` resolves through the table, a
/// conflicting re-registration fails the load, and unresolvable unions stay fail-closed.
class ExtensionUnionTypeMappingsTest {

    @Test
    void computeCoversAllBundledDumpUnionProperties() throws IOException {
        var mappings = ExtensionUnionTypeMappings.compute(ExtensionApiLoader.loadDefault());
        // Truth of the bundled 4.5.1 dump: 33 comma-joined property occurrences collapse to
        // 12 unique union strings (the texture-exclusion string alone appears 6 times).
        assertEquals(12, mappings.size());
        assertEquals("Material", mappings.get("CanvasItemMaterial,ShaderMaterial"));
        assertEquals("Material", mappings.get("BaseMaterial3D,ShaderMaterial"));
        assertEquals("Material", mappings.get("PanoramaSkyMaterial,ProceduralSkyMaterial,PhysicalSkyMaterial,ShaderMaterial"));
        assertEquals("CameraAttributes", mappings.get("CameraAttributesPractical,CameraAttributesPhysical"));
        assertEquals("Texture2D", mappings.get("CurveTexture,CurveXYZTexture"));
        assertEquals("Texture", mappings.get("Texture2D,Texture3D"));
        assertEquals("TextureLayered", mappings.get("Cubemap,CompressedCubemap,PlaceholderCubemap,TextureCubemapRD"));
        assertEquals("TextureLayered", mappings.get("Texture2DArray,CompressedTexture2DArray,PlaceholderTexture2DArray,Texture2DArrayRD"));
        assertEquals("Material", mappings.get("FogMaterial,ShaderMaterial"));
        assertEquals("Material", mappings.get("ParticleProcessMaterial,ShaderMaterial"));
        // Exclusion family maps to its first element.
        assertEquals("Mesh", mappings.get("Mesh,-PlaneMesh,-PointMesh,-QuadMesh,-RibbonTrailMesh"));
        assertEquals("Texture2D", mappings.get("Texture2D,-AnimatedTexture,-AtlasTexture,-CameraTexture,-CanvasTexture,-MeshTexture,-Texture2DRD,-ViewportTexture"));
    }

    @Test
    void enginePropertyTypesResolveThroughMappings() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertPropertyType(registry, "CanvasItem", "material", "Material");
        assertPropertyType(registry, "TileData", "material", "Material");
        assertPropertyType(registry, "Camera3D", "attributes", "CameraAttributes");
        assertPropertyType(registry, "CSGMesh3D", "mesh", "Mesh");
        assertPropertyType(registry, "Decal", "texture_albedo", "Texture2D");
        assertPropertyType(registry, "Environment", "adjustment_color_correction", "Texture");
        assertPropertyType(registry, "VisualShaderNodeCubemap", "cube_map", "TextureLayered");
    }

    @Test
    void parserResolvesUnionStringsThroughMapping() throws IOException {
        // The loadDefault() call above has already registered the dump mappings; re-loading is
        // idempotent, so the 2-arg (registry-less) parser path resolves through the table.
        ExtensionApiLoader.loadDefault();
        assertEquals("Material", ScopeTypeParsers.parseExtensionTypeMetadata(
                "CanvasItemMaterial,ShaderMaterial", "test union metadata").getTypeName());
        assertEquals("Texture2D", ScopeTypeParsers.parseExtensionTypeMetadata(
                "Texture2D,-AnimatedTexture,-AtlasTexture,-CameraTexture,-CanvasTexture,-MeshTexture,-Texture2DRD,-ViewportTexture",
                "test exclusion metadata").getTypeName());
    }

    @Test
    void conflictingMappingFailsTheLoadWithoutMutatingTheTable() throws IOException {
        ExtensionApiLoader.loadDefault();
        // A newer API version disagreeing on the SAME union string's resolving class must fail
        // the load (user-decided policy: this should never happen, so it is an error).
        assertThrows(IllegalStateException.class, () ->
                ScopeTypeParsers.registerUnionTypeMetadataMappings(
                        Map.of("CanvasItemMaterial,ShaderMaterial", "Resource")));
        // The conflict threw BEFORE any mutation: the table still resolves to Material.
        assertEquals("Material", ScopeTypeParsers.parseExtensionTypeMetadata(
                "CanvasItemMaterial,ShaderMaterial", "test union metadata").getTypeName());
        // Re-registering the identical mappings stays idempotent.
        ScopeTypeParsers.registerUnionTypeMetadataMappings(
                Map.of("CanvasItemMaterial,ShaderMaterial", "Material"));
    }

    @Test
    void unionsWithUnresolvableMembersStayFailClosed() {
        // Synthetic hierarchy: LeafB and LeafC share parent RootA.
        var rootA = gdClass("RootA", "", List.of());
        var leafB = gdClass("LeafB", "RootA", List.of());
        var leafC = gdClass("LeafC", "RootA", List.of());
        var holder = gdClass("Holder", "RootA", List.of(
                property("good", "LeafB,LeafC"),
                property("unknownMember", "LeafB,NoSuchClass"),
                property("malformed", "LeafB,"),
                property("excluded", "LeafB,-LeafC")
        ));
        var api = new ExtensionAPI(
                new ExtensionHeader(0, 0, 0, "", "", "", ""),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(rootA, leafB, leafC, holder),
                List.of(), List.of()
        );
        var mappings = ExtensionUnionTypeMappings.compute(api);
        assertEquals("RootA", mappings.get("LeafB,LeafC"));
        assertEquals("LeafB", mappings.get("LeafB,-LeafC"));
        // Unknown members and malformed fragments are skipped (strict failure preserved).
        assertFalse(mappings.containsKey("LeafB,NoSuchClass"));
        assertFalse(mappings.containsKey("LeafB,"));
    }

    @Test
    void inheritanceCycleSkipsTheUnionInsteadOfHanging() {
        // Malformed cycle CycA <-> CycB: the union must be skipped (fail-closed), and compute
        // must terminate instead of hanging the load thread.
        var cycA = gdClass("CycA", "CycB", List.of());
        var cycB = gdClass("CycB", "CycA", List.of());
        var holder = gdClass("CycHolder", "", List.of(property("cyc", "CycA,CycB")));
        var api = new ExtensionAPI(
                new ExtensionHeader(0, 0, 0, "", "", "", ""),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(cycA, cycB, holder),
                List.of(), List.of()
        );
        var mappings = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> ExtensionUnionTypeMappings.compute(api)
        );
        assertFalse(mappings.containsKey("CycA,CycB"));
    }

    private static void assertPropertyType(
            ClassRegistry registry,
            String className,
            String propertyName,
            String expectedTypeName
    ) {
        var lookup = registry.findPropertyInHierarchy(className, propertyName);
        assertNotNull(lookup, () -> className + "." + propertyName + " must resolve");
        assertEquals(expectedTypeName, lookup.property().getType().getTypeName(),
                () -> className + "." + propertyName + " type");
    }

    private static ExtensionGdClass gdClass(
            String name,
            String inherits,
            List<ExtensionGdClass.PropertyInfo> properties
    ) {
        return new ExtensionGdClass(
                name, true, true, inherits, "class", List.of(), List.of(), List.of(), properties, List.of()
        );
    }

    private static ExtensionGdClass.PropertyInfo property(String name, String type) {
        return new ExtensionGdClass.PropertyInfo(name, type, true, true, "", null, null, null);
    }
}
