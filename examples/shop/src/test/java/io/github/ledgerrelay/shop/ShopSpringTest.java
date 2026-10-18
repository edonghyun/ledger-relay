package io.github.ledgerrelay.shop;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 공용 PG·TB 위에서 워커까지 켠 앱 하나. 같은 설정의 테스트 클래스는 Spring 컨텍스트를 나눠 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ShopSpringTest {

    @DynamicPropertySource
    static void sharedEnvironment(DynamicPropertyRegistry registry) {
        ShopTestEnvironment.shared().properties(registry);
    }
}
