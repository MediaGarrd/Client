package wellatleastitried.mediagarrdclient.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestCsrfHeader {

    @Bean
    MockMvcBuilderCustomizer csrfHeader() {
        return builder -> builder.defaultRequest(get("/").header(CsrfCheck.HEADER, "1"));
    }
}
