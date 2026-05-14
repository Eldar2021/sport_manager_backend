package kg.sportmanager.session;

import kg.sportmanager.home.HomeTestSupport;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Session integration testleri için ortak helper'lar — HomeTestSupport'tan
 * venue/table/session DB-builder'larını miras alır.
 */
public abstract class SessionTestSupport extends HomeTestSupport {

    protected MockHttpServletRequestBuilder postWithBearer(String url, Object body, String token) throws Exception {
        return post(url)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder postEmptyWithBearer(String url, String token) {
        return post(url)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}");
    }
}
