package wellatleastitried.mediagarrdclient.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import jakarta.servlet.http.Cookie;
import wellatleastitried.mediagarrdclient.service.SessionService;

@TestConfiguration
public class TestSession {

    @Bean
    SessionService sessionService() {
        return new SessionService();
    }

    @Bean
    MockMvcBuilderCustomizer signedInRequests(SessionService sessions) {
        Cookie cookie = new Cookie(SessionGate.COOKIE, sessions.create());
        return builder -> builder.defaultRequest(get("/").cookie(cookie).header(CsrfCheck.HEADER, "1"));
    }
}
