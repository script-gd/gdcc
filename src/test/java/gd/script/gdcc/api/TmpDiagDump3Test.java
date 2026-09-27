package gd.script.gdcc.api;

import org.junit.jupiter.api.Test;

class TmpDiagDump3Test {
    @Test
    void dump() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("fixtures", "tmp");
        api.putFile("fixtures", "/src/c_bare.gd3", """
                class_name CandBare
                extends Node

                func f() -> void:
                    var fn = str
                """, "res://c_bare.gd3");
        api.putFile("fixtures", "/src/c_onready.gd3", """
                class_name CandOnready
                extends Node

                @onready var camera = $Camera3D
                """, "res://c_onready.gd3");
        api.putFile("fixtures", "/src/c_staticval.gd3", """
                class_name CandStaticVal
                extends Node

                func f() -> void:
                    var fn = Node.print
                """, "res://c_staticval.gd3");
        var result = api.analyze("fixtures", new AnalyzeOptions(true));
        System.out.println("DUMP outcome=" + result.outcome() + " lowering=" + result.loweringStatus());
        for (var d : result.diagnostics().asList()) {
            System.out.println("DUMP severity=" + d.severity() + " category=" + d.category()
                    + " path=" + d.sourcePath() + " range=" + d.range() + " message=" + d.message());
        }
    }
}
