package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/// DOM-based implementation of LirParser. Parses the XML structure into LIR entities.
public final class DomLirParser implements LirParser {
    @Override
    public @NotNull LirModule parse(@NotNull Reader reader, @NotNull String moduleName) throws Exception {
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
            var superName = cn.getAttribute("super");
            var isAbstract = Boolean.parseBoolean(cn.getAttribute("is_abstract"));
            var isTool = Boolean.parseBoolean(cn.getAttribute("is_tool"));

            var annotations = new HashMap<String, String>();
            var annNodes = cn.getElementsByTagName("annotation");
            for (int j = 0; j < annNodes.getLength(); j++) {
                var an = (Element) annNodes.item(j);
                var k = an.getAttribute("key");
                var v = an.getAttribute("value");
                annotations.put(k, v);
            }

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
                        var ptype = TypeParser.parse(pEl.getAttribute("type"));
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
                    var ptype = TypeParser.parse(pEl.getAttribute("type"));
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
                    fn.addAnnotations(annotationsF);

                    // parameters
                    var paramsNodes = fEl.getElementsByTagName("parameters");
                    if (paramsNodes.getLength() > 0) {
                        var pnode = (Element) paramsNodes.item(0);
                        var pList = pnode.getElementsByTagName("parameter");
                        for (int pi = 0; pi < pList.getLength(); pi++) {
                            var pEl = (Element) pList.item(pi);
                            var pname = pEl.getAttribute("name");
                            var ptype = TypeParser.parse(pEl.getAttribute("type"));
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
                            var ctype = TypeParser.parse(cEl.getAttribute("type"));
                            fn.addCapture(new LirCaptureDef(cname, ctype, fn));
                        }
                    }

                    // return_type
                    var retNodes = fEl.getElementsByTagName("return_type");
                    if (retNodes.getLength() > 0) {
                        var rEl = (Element) retNodes.item(0);
                        fn.setReturnType(TypeParser.parse(rEl.getAttribute("type")));
                    }

                    // variables
                    var varsNodes = fEl.getElementsByTagName("variables");
                    if (varsNodes.getLength() > 0) {
                        var vnode = (Element) varsNodes.item(0);
                        var vList = vnode.getElementsByTagName("variable");
                        for (int vi = 0; vi < vList.getLength(); vi++) {
                            var vEl = (Element) vList.item(vi);
                            var id = vEl.getAttribute("id");
                            var t = TypeParser.parse(vEl.getAttribute("type"));
                            fn.createAndAddVariable(id, t);
                        }
                    }

                    // basic_blocks
                    var bbsNodes = fEl.getElementsByTagName("basic_blocks");
                    if (bbsNodes.getLength() > 0) {
                        var bnode = (Element) bbsNodes.item(0);
                        var bbList = bnode.getElementsByTagName("basic_block");
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
                                        block.instructions().add(insn);
                                    }
                                }
                            }

                            fn.addBasicBlock(block);
                        }
                        // Note: we don't parse instruction lists in this initial implementation.
                        // The entry attribute is kept on XML but not stored on LirFunctionDef.
                    }

                    funcs.add(fn);
                }
            }

            classes.add(new LirClassDef(name, superName, isAbstract, isTool, annotations, signals, props, funcs));
        }

        return new LirModule(moduleName, classes);
    }

    @Override
    public @NotNull LirModule parse(@NotNull java.io.Reader reader) throws Exception {
        return parse(reader, "<parsed>");
    }
}
