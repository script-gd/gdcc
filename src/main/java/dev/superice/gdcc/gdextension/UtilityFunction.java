package dev.superice.gdcc.gdextension;

import java.util.List;

public record UtilityFunction(
        String name,
        String returnType,
        String category,
        boolean isVararg,
        int hash,
        List<FunctionArgument> arguments
) { }
