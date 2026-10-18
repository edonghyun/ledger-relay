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

/** demo 프로필로 띄운다. 화면은 Scenarios 탭의 데모 경로까지 부른다. */
@ActiveProfiles("demo")
@DisplayName("화면 — 정적 파일과 API 경로가 어긋나지 않는다 (03b §7.1)")
class StaticPageTest extends ShopSpringTest {

    /** shop.js 맨 위 {@code const API = { … };} 블록의 한 줄: {@code getStatus: '/api/status',} */
    private static final Pattern ENTRY = Pattern.compile("(get|post)\\w*\\s*:\\s*'([^']+)'");

    @Autowired
    MockMvcTester http;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("첫 화면에 Store·Operator·Scenarios 탭이 있다")
    void theIndexHasThreeTabs() {
        assertThat(http.get().uri("/")).as("첫 주소").hasForwardedUrl("index.html");
        assertThat(http.get().uri("/index.html")).hasStatus(HttpStatus.OK).bodyText()
                .contains("data-tab=\"store\"", "data-tab=\"operator\"", "data-tab=\"scenarios\"");
    }

    @Test
    @DisplayName("shop.js가 부르는 API 경로는 모두 서버에 있다 — 이름을 바꿔도 화면이 조용히 깨지지 않게")
    void everyApiPathTheUiCallsIsMapped() throws Exception {
        // given
        List<String[]> calls = apiCalls();
        assertThat(calls).as("shop.js의 API 항목").hasSizeGreaterThanOrEqualTo(10);

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
        assertThat(start).as("const API 블록").isNotNegative();
        String block = script.substring(start, script.indexOf("};", start));
        List<String[]> calls = new ArrayList<>();
        Matcher entry = ENTRY.matcher(block);
        while (entry.find()) {
            calls.add(new String[] {entry.group(1), entry.group(2)});
        }
        return calls;
    }
}
