package com.dinoparty.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Vite build and falls back to {@code index.html} for client-side routes such as {@code
 * /rooms/ABCD/play}, so a refresh or a shared invite link works. Ordered first so this {@code /**}
 * mapping is registered before Boot's default static mapping, which then steps aside.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.spa.enabled")
@Order(Ordered.HIGHEST_PRECEDENCE)
class SpaConfig implements WebMvcConfigurer {

  private final SpaProperties properties;

  SpaConfig(SpaProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // The resource handler drops an empty path before any resolver runs, so "/" needs its own
    // route.
    registry.addViewController("/").setViewName("forward:/index.html");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // Vite fingerprints everything under assets/, so those files never change under one name.
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations(properties.location() + "assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    registry
        .addResourceHandler("/**")
        .addResourceLocations(properties.location())
        .setCacheControl(CacheControl.noCache())
        // No resolver cache: it would keep one entry per distinct request path, unbounded.
        .resourceChain(false)
        .addResolver(new IndexFallbackResolver());
  }

  /** Real files first; extension-less paths become {@code index.html}; missing files stay 404. */
  private static final class IndexFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      // super keeps PathResourceResolver's checks that the file really sits under the location.
      Resource requested =
          resourcePath.isEmpty() ? null : super.getResource(resourcePath, location);
      if (requested != null) {
        return requested;
      }
      String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
      if (SpaProperties.isBackendPath("/" + resourcePath) || lastSegment.contains(".")) {
        return null;
      }
      return super.getResource("index.html", location);
    }
  }
}
