// File managed by WebFX (DO NOT EDIT MANUALLY)

module webfx.demo.emptiness.application {

    // Direct dependencies modules
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires webfx.kit.launcher;
    requires webfx.kit.webgl;
    requires webfx.platform.console;
    requires webfx.platform.resource;
    requires webfx.platform.storage;
    requires webfx.platform.typedarray;

    // Exported packages
    exports dev.webfx.demo.emptiness;

    // Resources packages
    opens dev.webfx.demo.emptiness;

    // Provided services
    provides javafx.application.Application with dev.webfx.demo.emptiness.EmptinessApplication;

}