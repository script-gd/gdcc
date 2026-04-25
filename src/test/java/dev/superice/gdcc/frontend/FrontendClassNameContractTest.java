package dev.superice.gdcc.frontend;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontendClassNameContractTest {
    @Test
    void derivesDefaultTopLevelSourceNameFromScriptFileName() {
        assertEquals("Player", FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path.of("player.gd")));
        assertEquals("PlayerController", FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path.of("player-controller.gd")));
        assertEquals("Gd2dPlayer", FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path.of("2d_player.gd")));
        assertEquals("Script", FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path.of("---.gd")));
    }
}
