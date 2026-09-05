package gd.script.gdcc.lir.parser;

import gd.script.gdcc.lir.*;
import gd.script.gdcc.lir.*;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/// DOM-based implementation of LirParser. Parses the XML structure into LIR entities.
public final class DomLirParser implements LirParser {
    private ClassRegistry classRegistry;

    public DomLirParser(@NotNull ClassRegistry classRegistry) {
        this.classRegistry = classRegistry;
    }

    public ClassRegistry getClassRegistry() {
        return classRegistry;
    }

    public void setClassRegistry(ClassRegistry classRegistry) {
        this.classRegistry = classRegistry;
    }

    @Override
    public @NotNull LirModule parse(@NotNull Reader reader, @NotNull String moduleName) throws Exception {
        if (classRegistry == null) {
            throw new IllegalStateException("ClassRegistry is not set on DomLirParser");
        }
        var dbf = DocumentBuilderFactory.newInstance();
        var builder = dbf.newDocumentBuilder();
        var doc = builder.parse(new org.xml.sax.InputSource(reader));
        var root = doc.getDocumentElement();
        if (!"ir".equals(root.getTagName())) {
            throw new IllegalArgumentException("Root element must be <ir>");
        }

        var classes = new ArrayList<LirClassDef>();
        var classNodes = root.getElementsByTagName("class_def");
        for (int i = 0; i < classNodes.getLength(); i++) {
            var cn = (Element) classNodes.item(i);
            var name = cn.getAttribute("name");
            // The serialized `super` attribute is canonical and is loaded verbatim into `ClassDef`.
            var superCanonicalName = cn.getAttribute("super");
            var isAbstract = Boolean.parseBoolean(cn.getAttribute("is_abstract"));
            var isTool = Boolean.parseBoolean(cn.getAttribute("is_tool"));

            // Only direct child annotations belong to the class itself: a recursive lookup would
            // also collect property/function annotations nested deeper in the class element.
            var annotations = readDirectChildAnnotations(cn);

            // signals
            var signals = new ArrayList<LirSignalDef>();
            var signalsNodes = cn.getElementsByTagName("signals");
            if (signalsNodes.getLength() > 0) {
                var snode = (Element) signalsNodes.item(0);
                var sn = snode.getElementsByTagName("signal");
                for (int si = 0; si < sn.getLength(); si++) {
                    var sEl = (Element) sn.item(si);
                    var sName = sEl.getAttribute("name");
                    var signal = new LirSignalDef(sName);
                    var params = sEl.getElementsByTagName("parameter");
                    for (int pi = 0; pi < params.getLength(); pi++) {
                        var pEl = (Element) params.item(pi);
                        var pname = pEl.getAttribute("name");
                        var ptype = parseTypeText(pEl.getAttribute("type"), LirTypeUseSite.SIGNAL_PARAMETER);
                        signal.addParameter(new LirParameterDef(pname, ptype, null, signal));
                    }
                    signals.add(signal);
                }
            }

            // properties
            var props = new ArrayList<LirPropertyDef>();
            var propNodes = cn.getElementsByTagName("properties");
            if (propNodes.getLength() > 0) {
                var pn = (Element) propNodes.item(0);
                var pList = pn.getElementsByTagName("property");
                for (int pi = 0; pi < pList.getLength(); pi++) {
                    var pEl = (Element) pList.item(pi);
                    var pname = pEl.getAttribute("name");
                    var ptype = parseTypeText(pEl.getAttribute("type"), LirTypeUseSite.PROPERTY);
                    var isStatic = Boolean.parseBoolean(pEl.getAttribute("is_static"));
                    var init = pEl.hasAttribute("init_func") ? pEl.getAttribute("init_func") : null;
                    var getter = pEl.hasAttribute("getter_func") ? pEl.getAttribute("getter_func") : null;
                    var setter = pEl.hasAttribute("setter_func") ? pEl.getAttribute("setter_func") : null;
                    var annotationsMap = new HashMap<String, String>();
                    var anns = pEl.getElementsByTagName("annotation");
                    for (int ai = 0; ai < anns.getLength(); ai++) {
                        var aEl = (Element) anns.item(ai);
                        annotationsMap.put(aEl.getAttribute("key"), aEl.getAttribute("value"));
                    }
                    props.add(new LirPropertyDef(pname, ptype, isStatic, init, getter, setter, annotationsMap));
                }
            }

            // functions
            var funcs = new ArrayList<LirFunctionDef>();
            var funcsNodes = cn.getElementsByTagName("functions");
            if (funcsNodes.getLength() > 0) {
                var fnode = (Element) funcsNodes.item(0);
                var fList = fnode.getElementsByTagName("function");
                for (int fi = 0; fi < fList.getLength(); fi++) {
                    var fEl = (Element) fList.item(fi);
                    var fname = fEl.getAttribute("name");
                    var isStaticF = Boolean.parseBoolean(fEl.getAttribute("is_static"));
                    var isAbstractF = Boolean.parseBoolean(fEl.getAttribute("is_abstract"));
                    var isLambdaF = Boolean.parseBoolean(fEl.getAttribute("is_lambda"));
                    var isVarargF = Boolean.parseBoolean(fEl.getAttribute("is_vararg"));
                    var isHiddenF = Boolean.parseBoolean(fEl.getAttribute("is_hidden"));
                    // Optional per contract; absent attribute parses as false (plain sync function).
                    // Like every other boolean attribute above, parsing is lenient
                    // (`Boolean.parseBoolean`): only the exact text "true" enables the marker, any
                    // other/missing value is false. The attribute name is exactly `is_coroutine`.
                    var coroutineF = Boolean.parseBoolean(fEl.getAttribute("is_coroutine"));

                    var annotationsF = new HashMap<String, String>();
                    var annsF = fEl.getElementsByTagName("annotation");
                    for (int ai = 0; ai < annsF.getLength(); ai++) {
                        var aEl = (Element) annsF.item(ai);
                        annotationsF.put(aEl.getAttribute("key"), aEl.getAttribute("value"));
                    }

                    var fn = new LirFunctionDef(fname);
                    fn.setStatic(isStaticF);
                    fn.setAbstract(isAbstractF);
                    fn.setLambda(isLambdaF);
                    fn.setVararg(isVarargF);
                    fn.setHidden(isHiddenF);
                    fn.setCoroutine(coroutineF);
                    fn.addAnnotations(annotationsF);

                    // parameters
                    var paramsNodes = fEl.getElementsByTagName("parameters");
                    if (paramsNodes.getLength() > 0) {
                        var pnode = (Element) paramsNodes.item(0);
                        var pList = pnode.getElementsByTagName("parameter");
                        for (int pi = 0; pi < pList.getLength(); pi++) {
                            var pEl = (Element) pList.item(pi);
                            var pname = pEl.getAttribute("name");
                            var ptype = parseTypeText(pEl.getAttribute("type"), LirTypeUseSite.FUNCTION_PARAMETER);
                            var defFunc = pEl.hasAttribute("default_value_func") ? pEl.getAttribute("default_value_func") : null;
                            fn.addParameter(new LirParameterDef(pname, ptype, defFunc, fn));
                        }
                    }

                    // captures: only parse capture descriptors; actual capture binding is deferred
                    var capsNodes = fEl.getElementsByTagName("captures");
                    if (capsNodes.getLength() > 0 && isLambdaF) {
                        var cnode = (Element) capsNodes.item(0);
                        var cList = cnode.getElementsByTagName("capture");
                        for (int ci = 0; ci < cList.getLength(); ci++) {
                            var cEl = (Element) cList.item(ci);
                            var cname = cEl.getAttribute("name");
                            var ctype = parseTypeText(cEl.getAttribute("type"), LirTypeUseSite.FUNCTION_CAPTURE);
                            fn.addCapture(new LirCaptureDef(cname, ctype, fn));
                        }
                    }

                    // return_type
                    var retNodes = fEl.getElementsByTagName("return_type");
                    if (retNodes.getLength() > 0) {
                        var rEl = (Element) retNodes.item(0);
                        var rtype = parseTypeText(rEl.getAttribute("type"), LirTypeUseSite.FUNCTION_RETURN);
                        fn.setReturnType(rtype);
                    }

                    // variables
                    var varsNodes = fEl.getElementsByTagName("variables");
                    if (varsNodes.getLength() > 0) {
                        var vnode = (Element) varsNodes.item(0);
                        var vList = vnode.getElementsByTagName("variable");
                        for (int vi = 0; vi < vList.getLength(); vi++) {
                            var vEl = (Element) vList.item(vi);
                            var id = vEl.getAttribute("id");
                            var t = parseTypeText(vEl.getAttribute("type"), LirTypeUseSite.FUNCTION_VARIABLE);
                            fn.createAndAddVariable(id, t);
                        }
                    }

                    // basic_blocks
                    var bbsNodes = fEl.getElementsByTagName("basic_blocks");
                    if (bbsNodes.getLength() > 0) {
                        var bnode = (Element) bbsNodes.item(0);
                        // Require explicit entry attribute when basic_blocks contains blocks
                        var bbList = bnode.getElementsByTagName("basic_block");
                        if (bbList.getLength() > 0) {
                            if (!bnode.hasAttribute("entry")) {
                                throw new IllegalArgumentException("<basic_blocks> must have an 'entry' attribute when basic_block children are present for function: " + fname);
                            }
                            var entryId = bnode.getAttribute("entry");
                            if (entryId.isEmpty()) {
                                throw new IllegalArgumentException("Empty 'entry' attribute on <basic_blocks> for function: " + fname);
                            }
                            fn.setEntryBlockId(entryId);
                        }
                        for (int bi = 0; bi < bbList.getLength(); bi++) {
                            var bbEl = (Element) bbList.item(bi);
                            var bbid = bbEl.getAttribute("id");
                            var block = new LirBasicBlock(bbid);

                            // parse textual instruction list inside the basic_block element
                            var text = bbEl.getTextContent();
                            if (text != null && !text.isBlank()) {
                                var parser = new SimpleLirBlockInsnParser();
                                try (var sr = new StringReader(text)) {
                                    var insns = parser.parse(sr);
                                    for (var insn : insns) {
                                        block.appendInstruction(insn);
                                    }
                                }
                            }

                            fn.addBasicBlock(block);
                        }
                    }

                    funcs.add(fn);
                }
            }

            classes.add(new LirClassDef(name, superCanonicalName, isAbstract, isTool, annotations, signals, props, funcs));
        }

        return new LirModule(moduleName, classes);
    }

    /// Reads only the `annotation` elements that are direct children of `element`.
    /// `getElementsByTagName` cannot be used for the class level because it recursively descends
    /// into `<properties>`/`<functions>` and would pollute the class map with member annotations.
    private static @NotNull Map<String, String> readDirectChildAnnotations(@NotNull Element element) {
        var annotations = new HashMap<String, String>();
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && "annotation".equals(child.getTagName())) {
                annotations.put(child.getAttribute("key"), child.getAttribute("value"));
            }
        }
        return annotations;
    }

    @Override
    public @NotNull LirModule parse(@NotNull java.io.Reader reader) throws Exception {
        return parse(reader, "<parsed>");
    }

    /// LIR XML keeps compiler-only types on a dedicated grammar and only accepts them for local
    /// function variables. All public ABI-like surfaces continue to reuse source-facing parsing.
    private @NotNull GdType parseTypeText(@NotNull String rawTypeText, @NotNull LirTypeUseSite useSite) {
        var typeText = rawTypeText.trim();
        if (typeText.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse type for " + useSite.displayName() + ": blank type text");
        }
        var compilerOnlyType = tryParseCompilerOnlyType(typeText, useSite);
        if (compilerOnlyType != null) {
            return compilerOnlyType;
        }

        var parsedType = classRegistry.findType(typeText);
        if (parsedType != null) {
            return parsedType;
        }
        throw new IllegalArgumentException("Cannot parse type for " + useSite.displayName() + ": " + rawTypeText);
    }

    private @Nullable GdType tryParseCompilerOnlyType(@NotNull String typeText, @NotNull LirTypeUseSite useSite) {
        if (!typeText.startsWith("compiler::")) {
            return null;
        }
        if (!useSite.allowCompilerOnlyType()) {
            throw new IllegalArgumentException(
                    "compiler-only type leaked into " + useSite.displayName() + ": " + typeText
            );
        }
        return switch (typeText) {
            case GdccForRangeIterType.LIR_TYPE_TEXT -> GdccForRangeIterType.FOR_RANGE_ITER;
            case GdccForVariantIterType.LIR_TYPE_TEXT -> GdccForVariantIterType.FOR_VARIANT_ITER;
            case GdccForStringIterType.LIR_TYPE_TEXT -> GdccForStringIterType.FOR_STRING_ITER;
            case GdccForArrayIterType.LIR_TYPE_TEXT -> GdccForArrayIterType.FOR_ARRAY_ITER;
            case GdccForDictionaryIterType.LIR_TYPE_TEXT -> GdccForDictionaryIterType.FOR_DICTIONARY_ITER;
            case GdccForFloatIterType.LIR_TYPE_TEXT -> GdccForFloatIterType.FOR_FLOAT_ITER;
            case GdccCoroStateType.LIR_TYPE_TEXT -> GdccCoroStateType.CORO_STATE;
            default -> {
                var packed = GdccForPackedArrayIterType.findByLirTypeText(typeText);
                if (packed != null) {
                    yield packed;
                }
                throw new IllegalArgumentException("Unknown compiler-only type text: " + typeText);
            }
        };
    }
}
