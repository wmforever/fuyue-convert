package com.fuyue.formatconverter.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTokenFilterTest {
    @Test void rejectsProtectedTaskApiWithoutToken() throws ServletException, IOException {
        ApiTokenFilter filter = new ApiTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks/id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test void allowsProtectedTaskApiWithBearerToken() throws ServletException, IOException {
        ApiTokenFilter filter = new ApiTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks/id");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test void allowsHealthWhenTokenIsConfigured() throws ServletException, IOException {
        ApiTokenFilter filter = new ApiTokenFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
