package edu.camserver.app.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser origins allowed to call the API, configured with {@code app.cors.allowed-origins}.
 *
 * <p>The Nuxt frontend proxies {@code /api/backend/**} to this backend and forwards the browser's
 * {@code Origin} header unchanged, so every POST arrives here looking like a cross-origin request
 * from the public site (same-origin GETs carry no Origin header, which is why only writes failed).
 * Spring rejects such requests with {@code 403 Invalid CORS request} unless the origin is allowed,
 * so the list must contain every host name the site is served under.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final List<String> allowedOriginPatterns;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOriginPatterns = allowedOrigins == null
                ? List.of()
                : Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOriginPatterns.isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
