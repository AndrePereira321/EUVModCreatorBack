package com.euvmodcreator.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Puts a request id and the client's IP into the MDC, so every log line written while handling the request carries
 * them. Runs before Spring Security, so its filters' log lines carry them too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MdcFilter extends OncePerRequestFilter {

    static final String REQUEST_ID = "requestId";

    static final String CLIENT_IP = "clientIp";

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        response.setHeader(REQUEST_ID_HEADER, requestId);

        MDC.put(REQUEST_ID, requestId);
        MDC.put(CLIENT_IP, request.getRemoteAddr());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            MDC.remove(CLIENT_IP);
        }
    }

}
