package io.github.ledgerrelay.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.shop.ShopSpringTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/** Starts with the demo profile, because the UI also calls the demo paths of the Scenarios tab. */
@ActiveProfiles("demo")
@DisplayName("UI — the static files and the API paths stay in sync")
class StaticPageTest extends ShopSpringTest {

    /** One line of the {@code const API = { … };} block at the top of shop.js: {@code getStatus: '/api/status',} */
    private static final Pattern ENTRY = Pattern.compile("(get|post)\\w*\\s*:\\s*'([^']+)'");

    @Autowired
    MockMvcTester http;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("The first page has the Store, Operator and Scenarios tabs")
    void theIndexHasThreeTabs() {
        assertThat(http.get().uri("/")).as("root path").hasForwardedUrl("index.html");
        assertThat(http.get().uri("/index.html")).hasStatus(HttpStatus.OK).bodyText()
                .contains("data-tab=\"store\"", "data-tab=\"operator\"", "data-tab=\"scenarios\"");
    }

    @Test
    @DisplayName("Every API path shop.js calls is mapped on the server — so that a rename never silently breaks the UI")
    void everyApiPathTheUiCallsIsMapped() throws Exception {
        // given
        List<String[]> calls = apiCalls();
        assertThat(calls).as("API entries in shop.js").hasSizeGreaterThanOrEqualTo(10);

        // then
        for (String[] call : calls) {
            String path = call[1].replace("{id}", "1").replace("{commandId}", "x").replaceFirst("\\?.*$", "");
            MockHttpServletRequest request = new MockHttpServletRequest(call[0].toUpperCase(), path);
            ServletRequestPathUtils.parseAndCache(request);
            assertThat(mappings.getHandler(request)).as(call[0].toUpperCase() + " " + call[1]).isNotNull();
        }
    }

    private static List<String[]> apiCalls() throws IOException {
        String script;
        try (InputStream in = StaticPageTest.class.getResourceAsStream("/static/shop.js")) {
            assertThat(in).as("static/shop.js").isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = script.indexOf("const API = {");
        assertThat(start).as("const API block").isNotNegative();
        String block = script.substring(start, script.indexOf("};", start));
        List<String[]> calls = new ArrayList<>();
        Matcher entry = ENTRY.matcher(block);
        while (entry.find()) {
            calls.add(new String[] {entry.group(1), entry.group(2)});
        }
        return calls;
    }
}
