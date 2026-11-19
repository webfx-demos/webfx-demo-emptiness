// File managed by WebFX (DO NOT EDIT MANUALLY)
package dev.webfx.platform.resource.j2cl;

import org.treblereel.j2cl.processors.annotations.GWT3Resource;
import org.treblereel.j2cl.processors.common.resources.ClientBundle;
import org.treblereel.j2cl.processors.common.resources.TextResource;
import dev.webfx.platform.resource.spi.impl.web.WebResourceBundleBase;

@GWT3Resource
public interface J2clEmbedResourcesBundle extends ClientBundle {

    J2clEmbedResourcesBundle R = J2clEmbedResourcesBundleImpl.INSTANCE;

    @Source("/dev/webfx/demo/emptiness/sphereFragmentShader.glsl")
    TextResource r1();

    @Source("/dev/webfx/demo/emptiness/sphereVertexShader.glsl")
    TextResource r2();

    @Source("/dev/webfx/platform/meta/exe/exe.properties")
    TextResource r3();

    final class ProvidedJ2clResourceBundle extends WebResourceBundleBase {
        public ProvidedJ2clResourceBundle() {
            registerResource("dev/webfx/demo/emptiness/sphereFragmentShader.glsl", () -> R.r1().getText());
            registerResource("dev/webfx/demo/emptiness/sphereVertexShader.glsl", () -> R.r2().getText());
            registerResource("dev/webfx/platform/meta/exe/exe.properties", () -> R.r3().getText());
        }
    }
}