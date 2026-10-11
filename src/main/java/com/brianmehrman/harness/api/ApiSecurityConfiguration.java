package com.brianmehrman.harness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("api")
public class ApiSecurityConfiguration {
    @Bean ApiMutationFilter apiMutationFilter() { return new ApiMutationFilter(); }

    public static final class ApiMutationFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (!request.getRequestURI().startsWith("/api/")) {
                chain.doFilter(request, response);
                return;
            }
            String host = request.getHeader("Host");
            if (host == null) host = request.getServerName() + ":" + request.getServerPort();
            if (!host.toLowerCase(Locale.ROOT).matches("(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?")) {
                response.sendError(403, "Loopback host required");
                return;
            }
            if (!(request.getMethod().equals("POST") || request.getMethod().equals("PUT") ||
                      request.getMethod().equals("PATCH") || request.getMethod().equals("DELETE"))) {
                chain.doFilter(request, response);
                return;
            }
            String origin = request.getHeader("Origin");
            String site = request.getHeader("Sec-Fetch-Site");
            String expected = request.getScheme() + "://" +
                    host;
            if (origin != null && !origin.equals(expected) ||
                    site != null && !site.equals("same-origin") && !site.equals("none")) {
                response.sendError(403, "Cross-origin mutation rejected");
                return;
            }
            boolean json;
            try { json = MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())); }
            catch (RuntimeException invalid) { json = false; }
            if (!json) {
                response.sendError(415, "JSON content type required");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
