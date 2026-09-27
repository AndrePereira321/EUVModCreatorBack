package com.euvmodcreator;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

// Each top-level package is a module; its sub-packages are internal to it. Fails on a cycle between modules, or on
// a module using another's sub-package.
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(EuvModCreatorBackApplication.class).verify();
    }

}
