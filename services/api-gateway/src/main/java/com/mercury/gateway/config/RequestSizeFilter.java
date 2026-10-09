package com.mercury.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Refuses oversized request bodies up front (by their declared length) instead of reading and forwarding them. A chunked body
 * declares no length: {@link BodylessRequestFilter} counts those as they are read, and refuses them with the same response.
 */
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            refuse(response, maxBytes);
            return;
        }
        chain.doFilter(request, response);
    }

    /** the one 413 the gateway answers with, whichever way the size was found out */
    static void refuse(HttpServletResponse response, long maxBytes) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":413,\"error\":\"PAYLOAD_TOO_LARGE\","
                + "\"message\":\"The request body is larger than " + maxBytes + " bytes\"}");
    }
}
