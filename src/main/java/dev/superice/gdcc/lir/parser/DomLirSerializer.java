package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.*;

import java.io.StringWriter;
import java.io.Writer;
import java.util.*;

/// DOM-based implementation of LirSerializer.
public final class DomLirSerializer implements LirSerializer {
    @Override
    public void serialize(@NotNull LirModule module, @NotNull Writer out) throws Exception {
        var docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = docFactory.newDocumentBuilder();
        Document doc = builder.newDocument();

        Element irEl = doc.createElement("ir");
        doc.appendChild(irEl);

        var insnSerializer = new SimpleLirBlockInsnSerializer();

        for (var cls : module.getClassDefs()) {
            Element classEl = doc.createElement("class_def");
            classEl.setAttribute("name", cls.getName());
            classEl.setAttribute("super", cls.getSuperName());
            classEl.setAttribute("is_abstract", Boolean.toString(cls.isAbstract()));
            classEl.setAttribute("is_tool", Boolean.toString(cls.isTool()));

            // annotations
            for (var e : cls.getAnnotations().entrySet()) {
                Element ann = doc.createElement("annotation");
                ann.setAttribute("key", e.getKey());
                ann.setAttribute("value", e.getValue());
                classEl.appendChild(ann);
            }

            // signals
            Element signalsEl = doc.createElement("signals");
            for (var s : cls.getSignals()) {
                Element sEl = doc.createElement("signal");
                sEl.setAttribute("name", s.getName());
                for (int i = 0; i < s.getParameterCount(); i++) {
                    var p = s.getParameter(i);
                    Element pEl = doc.createElement("parameter");
                    pEl.setAttribute("name", Objects.requireNonNull(p).name());
                    pEl.setAttribute("type", p.type().getTypeName());
                    sEl.appendChild(pEl);
                }
                signalsEl.appendChild(sEl);
            }
            classEl.appendChild(signalsEl);

            // properties
            Element propsEl = doc.createElement("properties");
            for (var prop : cls.getProperties()) {
                Element pEl = doc.createElement("property");
                pEl.setAttribute("name", prop.getName());
                pEl.setAttribute("type", prop.getType().getTypeName());
                pEl.setAttribute("is_static", Boolean.toString(prop.isStatic()));
                if (prop.getInitFunc() != null) pEl.setAttribute("init_func", prop.getInitFunc());
                if (prop.getGetterFunc() != null) pEl.setAttribute("getter_func", prop.getGetterFunc());
                if (prop.getSetterFunc() != null) pEl.setAttribute("setter_func", prop.getSetterFunc());
                for (var e : prop.getAnnotations().entrySet()) {
                    Element ann = doc.createElement("annotation");
                    ann.setAttribute("key", e.getKey());
                    ann.setAttribute("value", e.getValue());
                    pEl.appendChild(ann);
                }
                propsEl.appendChild(pEl);
            }
            classEl.appendChild(propsEl);

            // functions
            Element funcsEl = doc.createElement("functions");
            for (var fn : cls.getFunctions()) {
                Element fEl = doc.createElement("function");
                fEl.setAttribute("name", fn.getName());
                fEl.setAttribute("is_static", Boolean.toString(fn.isStatic()));
                fEl.setAttribute("is_abstract", Boolean.toString(fn.isAbstract()));
                fEl.setAttribute("is_lambda", Boolean.toString(fn.isLambda()));
                fEl.setAttribute("is_vararg", Boolean.toString(fn.isVararg()));
                fEl.setAttribute("is_hidden", Boolean.toString(fn.isHidden()));

                for (var e : fn.getAnnotations().entrySet()) {
                    Element ann = doc.createElement("annotation");
                    ann.setAttribute("key", e.getKey());
                    ann.setAttribute("value", e.getValue());
                    fEl.appendChild(ann);
                }

                // parameters
                Element paramsEl = doc.createElement("parameters");
                for (int i = 0; i < fn.getParameterCount(); i++) {
                    var p = fn.getParameter(i);
                    if (p == null) {
                        throw new IllegalStateException("Function " + fn.getName() + " has null parameter at index " + i);
                    }
                    Element pEl = doc.createElement("parameter");
                    pEl.setAttribute("name", p.name());
                    pEl.setAttribute("type", p.type().getTypeName());
                    if (p.defaultValueFunc() != null) pEl.setAttribute("default_value_func", p.defaultValueFunc());
                    paramsEl.appendChild(pEl);
                }
                fEl.appendChild(paramsEl);

                // captures - we do not serialize capture bodies here; write empty container for forward-compat
                Element capsEl = doc.createElement("captures");
                fEl.appendChild(capsEl);

                // return type
                Element retEl = doc.createElement("return_type");
                retEl.setAttribute("type", fn.getReturnType().getTypeName());
                fEl.appendChild(retEl);

                // variables
                Element varsEl = doc.createElement("variables");
                for (var v : fn.getVariables().values()) {
                    Element vEl = doc.createElement("variable");
                    vEl.setAttribute("id", v.id());
                    vEl.setAttribute("type", v.type().getTypeName());
                    varsEl.appendChild(vEl);
                }
                fEl.appendChild(varsEl);

                Element bbsEl = doc.createElement("basic_blocks");
                // set entry to first basic block id if present
                if (fn.getBasicBlockCount() > 0) {
                    var it = fn.iterator();
                    if (it.hasNext()) {
                        bbsEl.setAttribute("entry", it.next().id());
                    }
                }
                for (var bb : fn) {
                    Element bbEl = doc.createElement("basic_block");
                    bbEl.setAttribute("id", bb.id());

                    // serialize instructions for this basic block into textual form and attach as text node
                    try (var sw = new StringWriter()) {
                        insnSerializer.serialize(bb.instructions(), sw);
                        var textNode = doc.createTextNode(sw.toString());
                        bbEl.appendChild(textNode);
                    }

                    bbsEl.appendChild(bbEl);
                }
                fEl.appendChild(bbsEl);

                funcsEl.appendChild(fEl);
            }
            classEl.appendChild(funcsEl);

            irEl.appendChild(classEl);
        }

        // write DOM to writer
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(doc), new StreamResult(out));
    }
}
