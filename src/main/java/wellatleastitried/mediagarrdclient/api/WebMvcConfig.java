package wellatleastitried.mediagarrdclient.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SessionGate sessionGate;

    public WebMvcConfig(SessionGate sessionGate) {
        this.sessionGate = sessionGate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionGate).addPathPatterns("/**");
    }
}
