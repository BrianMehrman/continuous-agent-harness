package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiSecurityTest {
    @Test void foreignOriginAndNonJsonMutationsAreRejected() throws Exception {
        var filter = new ApiSecurityConfiguration.ApiMutationFilter();
        for (String origin : new String[]{"https://foreign.example", "null"}) {
            var request = request("application/json");
            request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("foreign origin passed"));
            assertEquals(403, response.getStatus());
        }
        for (String mediaType : new String[]{"text/plain", "application/x-www-form-urlencoded"}) {
            var request = request(mediaType);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("non-JSON mutation passed"));
            assertEquals(415, response.getStatus());
        }
        var request = request("application/json");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("cross-site mutation passed"));
        assertEquals(403, response.getStatus());
    }

    private static MockHttpServletRequest request(String type) {
        var request = new MockHttpServletRequest("POST", "/api/runs");
        request.setContentType(type);
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        return request;
    }
}
