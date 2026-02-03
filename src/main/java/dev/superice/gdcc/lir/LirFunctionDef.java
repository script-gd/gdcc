package dev.superice.gdcc.lir;

import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.*;

/**
 * XML entity: <function ...> ... </function>.
 */
@SuppressWarnings("unused")
public final class LirFunctionDef implements LirParameterEntity, FunctionDef, Iterable<LirBasicBlock> {
    private @NotNull String name;
    private boolean isStatic;
    private boolean isAbstract;
    private boolean isLambda;
    private boolean isVararg;
    private boolean isHidden;
    private Map<String, String> annotations;
    private final List<LirParameterDef> parameters;
    private final Map<String, LirCaptureDef> captures;
    private @NotNull GdType returnType = GdVoidType.VOID;
    private final Map<String, LirVariable> variables;
    private int tmpVarCounter = 0;
    private final SequencedMap<String, LirBasicBlock> basicBlocks;
    private @NotNull String entryBlockId = ""; // default to empty string (no entry set)

    public LirFunctionDef(
            String name,
            boolean isStatic,
            boolean isAbstract,
            boolean isLambda,
            boolean isVararg,
            boolean isHidden,
            Map<String, String> annotations,
            List<LirParameterDef> parameters,
            Map<String, LirCaptureDef> captures,
            GdType returnType,
            Map<String, LirVariable> variables,
            SequencedMap<String, LirBasicBlock> basicBlocks
    ) {
        this.name = Objects.requireNonNull(name);
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;
        this.isLambda = isLambda;
        this.isVararg = isVararg;
        this.isHidden = isHidden;
        this.annotations = new HashMap<>(Objects.requireNonNull(annotations));
        this.parameters = new ArrayList<>(Objects.requireNonNull(parameters));
        this.captures = new HashMap<>(Objects.requireNonNull(captures));
        this.returnType = Objects.requireNonNull(returnType);
        this.variables = new HashMap<>(Objects.requireNonNull(variables));
        this.basicBlocks = new LinkedHashMap<>(basicBlocks);
        // Do NOT default entryBlockId to the first basic block; require explicit setting
    }

    /**
     * Create a function with explicit entry block id and initial basic blocks.
     */
    public LirFunctionDef(
            String name,
            boolean isStatic,
            boolean isAbstract,
            boolean isLambda,
            boolean isVararg,
            boolean isHidden,
            Map<String, String> annotations,
            List<LirParameterDef> parameters,
            Map<String, LirCaptureDef> captures,
            GdType returnType,
            Map<String, LirVariable> variables,
            SequencedMap<String, LirBasicBlock> basicBlocks,
            @NotNull String entryBlockId
    ) {
        this.name = Objects.requireNonNull(name);
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;
        this.isLambda = isLambda;
        this.isVararg = isVararg;
        this.isHidden = isHidden;
        this.annotations = new HashMap<>(Objects.requireNonNull(annotations));
        this.parameters = new ArrayList<>(Objects.requireNonNull(parameters));
        this.captures = new HashMap<>(Objects.requireNonNull(captures));
        this.returnType = Objects.requireNonNull(returnType);
        this.variables = new HashMap<>(Objects.requireNonNull(variables));
        this.basicBlocks = new LinkedHashMap<>(basicBlocks);
        this.entryBlockId = Objects.requireNonNull(entryBlockId);
    }

    public LirFunctionDef(@NotNull String name) {
        this.name = name;
        this.isStatic = false;
        this.isAbstract = false;
        this.isLambda = false;
        this.isVararg = false;
        this.isHidden = false;
        this.annotations = new HashMap<>();
        this.parameters = new ArrayList<>();
        this.captures = new HashMap<>();
        this.variables = new HashMap<>();
        this.basicBlocks = new LinkedHashMap<>();
        this.entryBlockId = "";
    }

    /**
     * Create a function and set its explicit entry block id.
     */
    public LirFunctionDef(@NotNull String name, @NotNull String entryBlockId) {
        this(name);
        this.entryBlockId = Objects.requireNonNull(entryBlockId);
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isLambda() {
        return isLambda;
    }

    public void setLambda(boolean lambda) {
        isLambda = lambda;
    }

    public boolean isVararg() {
        return isVararg;
    }

    public void setVararg(boolean vararg) {
        isVararg = vararg;
    }

    public boolean isHidden() {
        return isHidden;
    }

    public void setHidden(boolean hidden) {
        isHidden = hidden;
    }

    public @NotNull Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(@NotNull Map<String, String> annotations) {
        this.annotations = new HashMap<>(annotations);
    }

    public void addAnnotation(@NotNull String key, @NotNull String value) {
        this.annotations.put(key, value);
    }

    public void addAnnotations(@NotNull Map<String, String> annotations) {
        this.annotations.putAll(annotations);
    }

    public void clearAnnotations() {
        this.annotations.clear();
    }

    public void addParameter(@NotNull LirParameterDef parameter) {
        this.parameters.add(parameter);
        this.variables.put(parameter.name(), new LirVariable(parameter.name(), parameter.type(), true, this));
    }

    public void addParameter(int index, @NotNull LirParameterDef parameter) {
        this.parameters.add(index, parameter);
        this.variables.put(parameter.name(), new LirVariable(parameter.name(), parameter.type(), true, this));
    }

    public void clearParameters() {
        for (var param : parameters) {
            this.variables.remove(param.name());
        }
        this.parameters.clear();
    }

    public @Nullable LirParameterDef getParameter(@NotNull String name) {
        for (var param : parameters) {
            if (param.name().equals(name)) {
                return param;
            }
        }
        return null;
    }

    public @Nullable LirParameterDef getParameter(int index) {
        if (index < 0 || index >= parameters.size()) {
            return null;
        }
        return parameters.get(index);
    }

    @Override
    public boolean removeParameter(@NotNull String name) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(name)) {
                parameters.remove(i);
                this.variables.remove(name);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean removeParameter(int index) {
        if (index < 0 || index >= parameters.size()) {
            return false;
        }
        var param = parameters.remove(index);
        this.variables.remove(param.name());
        return true;
    }

    public int getParameterCount() {
        return parameters.size();
    }

    @Override
    public @NotNull @UnmodifiableView List<? extends ParameterDef> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    public void addCapture(@NotNull LirCaptureDef capture) {
        if (!isLambda) {
            throw new IllegalStateException("Only lambda functions can have captures.");
        }
        this.captures.put(capture.name(), capture);
        this.variables.put(capture.name(), new LirVariable(capture.name(), capture.type(), this));
    }

    public void clearCaptures() {
        for (var capture : captures.values()) {
            this.variables.remove(capture.name());
        }
        this.captures.clear();
    }

    public @Nullable LirCaptureDef getCapture(@NotNull String name) {
        if (!isLambda) {
            return null;
        }
        return captures.get(name);
    }

    public boolean removeCapture(@NotNull String name) {
        if (!isLambda) {
            return false;
        }
        var removed = captures.remove(name);
        if (removed != null) {
            this.variables.remove(name);
            return true;
        }
        return false;
    }

    public int getCaptureCount() {
        if (!isLambda) {
            return 0;
        }
        return captures.size();
    }

    public @UnmodifiableView Map<String, LirCaptureDef> getCaptures() {
        return Collections.unmodifiableMap(captures);
    }

    public @NotNull GdType getReturnType() {
        return returnType;
    }

    public void setReturnType(@NotNull GdType returnType) {
        this.returnType = returnType;
    }

    public @UnmodifiableView Map<String, LirVariable> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public @Nullable LirVariable getVariableById(@NotNull String id) {
        return this.variables.get(id);
    }

    public @NotNull LirVariable createAndAddTmpVariable(@NotNull GdType type) {
        var newId = Integer.toString(tmpVarCounter++);
        var tmpVar = new LirVariable(newId, type, this);
        variables.put(newId, tmpVar);
        return tmpVar;
    }

    public @Nullable LirVariable createAndAddVariable(@NotNull String id, @NotNull GdType type) {
        if (this.variables.containsKey(id)) {
            return null;
        }
        var var = new LirVariable(id, type, this);
        this.variables.put(id, var);
        return var;
    }

    public @NotNull LirVariable createAndAddTmpRefVariable(@NotNull GdType type) {
        var newId = Integer.toString(tmpVarCounter++);
        var tmpVar = new LirVariable(newId, type, true, this);
        variables.put(newId, tmpVar);
        return tmpVar;
    }

    public @Nullable LirVariable createAndAddRefVariable(@NotNull String id, @NotNull GdType type) {
        if (this.variables.containsKey(id)) {
            return null;
        }
        var var = new LirVariable(id, type, true, this);
        this.variables.put(id, var);
        return var;
    }

    public boolean hasVariable(@NotNull String id) {
        return this.variables.containsKey(id);
    }

    public boolean hasVariable(int id) {
        return this.variables.containsKey(Integer.toString(id));
    }

    public boolean checkVariableRef(@NotNull String id) {
        var var = this.variables.get(id);
        return var != null && var.ref();
    }

    public boolean checkVariableRef(int id) {
        var var = this.variables.get(Integer.toString(id));
        return var != null && var.ref();
    }

    public boolean checkVariableParameter(@NotNull String id) {
        var var = this.variables.get(id);
        if (var == null) {
            return false;
        }
        for (var param : parameters) {
            if (param.name().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkVariableParameter(int id) {
        return checkVariableParameter(Integer.toString(id));
    }

    /**
     * Remove variable by id. Parameter and capture variables cannot be removed.
     *
     * @param id Variable id.
     * @return True if the variable was removed, false if it did not exist.
     */
    public boolean removeVariable(@NotNull String id) {
        return this.variables.remove(id) != null;
    }

    /**
     * Remove variable by id.
     *
     * @param id Variable id.
     * @return True if the variable was removed, false if it did not exist.
     */
    public boolean removeVariable(int id) {
        return this.variables.remove(Integer.toString(id)) != null;
    }

    @Override
    public @NotNull Iterator<LirBasicBlock> iterator() {
        return basicBlocks.values().iterator();
    }

    public void addBasicBlock(@NotNull LirBasicBlock block) {
        this.basicBlocks.put(block.id(), block);
    }

    public int getBasicBlockCount() {
        return this.basicBlocks.size();
    }

    public @Nullable LirBasicBlock getBasicBlock(@NotNull String id) {
        return this.basicBlocks.get(id);
    }

    public boolean hasBasicBlock(@NotNull String id) {
        return this.basicBlocks.containsKey(id);
    }

    public boolean removeBasicBlock(@NotNull String id) {
        return this.basicBlocks.remove(id) != null;
    }

    /**
     * Get the id of the entry basic block for this function. Empty string means not set.
     */
    public @NotNull String getEntryBlockId() {
        return entryBlockId;
    }

    /**
     * Set the id of the entry basic block for this function. Use empty string to unset.
     */
    public void setEntryBlockId(@NotNull String entryBlockId) {
        this.entryBlockId = Objects.requireNonNull(entryBlockId);
        // if entryBlockId is not empty, ensure it exists and move to the front
        if (!this.entryBlockId.isEmpty() && this.basicBlocks.containsKey(this.entryBlockId)) {
            var tmp = this.basicBlocks.remove(this.entryBlockId);
            this.basicBlocks.putFirst(this.entryBlockId, tmp);
        }
    }
}
