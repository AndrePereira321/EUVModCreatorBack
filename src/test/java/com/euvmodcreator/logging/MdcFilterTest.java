package com.euvmodcreator.logging;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MdcFilterTest {

    private final MdcFilter filter = new MdcFilter();

    @Test
    void requestRunsWithItsIdAndClientIpInTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request, response, (req, res) -> seen.putAll(MDC.getCopyOfContextMap()));

        assertThat(seen)
                .containsEntry(MdcFilter.CLIENT_IP, "203.0.113.7")
                .containsEntry(MdcFilter.REQUEST_ID, response.getHeader(MdcFilter.REQUEST_ID_HEADER));
        assertThat(seen.get(MdcFilter.REQUEST_ID)).matches("[0-9a-f]{16}");
    }

    @Test
    void everyRequestGetsItsOwnId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> { });

        assertThat(first.getHeader(MdcFilter.REQUEST_ID_HEADER))
                .isNotEqualTo(second.getHeader(MdcFilter.REQUEST_ID_HEADER));
    }

    // A platform thread goes back to a pool after the request; values left behind would label the next one.
    @Test
    void mdcIsEmptyAfterwardsEvenWhenTheRequestFails() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(MdcFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(MdcFilter.CLIENT_IP)).isNull();
    }

}
