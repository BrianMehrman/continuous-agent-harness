package com.brianmehrman.harness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
            if (!request.getRequestURI().startsWith("/api/") ||
                    !(request.getMethod().equals("POST") || request.getMethod().equals("PUT") ||
                      request.getMethod().equals("PATCH") || request.getMethod().equals("DELETE"))) {
                chain.doFilter(request, response);
                return;
            }
            String origin = request.getHeader("Origin");
            String site = request.getHeader("Sec-Fetch-Site");
            String host = request.getHeader("Host");
            String expected = request.getScheme() + "://" +
                    (host == null ? request.getServerName() + ":" + request.getServerPort() : host);
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
