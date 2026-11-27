package wellatleastitried.mediagarrdclient.api;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import wellatleastitried.mediagarrdclient.service.SessionService;

@Component
public class SessionGate implements HandlerInterceptor {

    static final String COOKIE = "mg_session";

    private final SessionService sessions;

    public SessionGate(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        if (!CsrfCheck.allows(request)) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"Cross-site request rejected\"}");
        }
        if (isStaticContent(handler) || isPublic(handler)) {
            return true;
        }
        if (sessions.validate(sessionToken(request))) {
            return true;
        }
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"Login required\",\"login\":\"required\"}");
    }

    static String sessionToken(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, COOKIE);
        return cookie == null ? null : cookie.getValue();
    }

    private static boolean isStaticContent(Object handler) {
        return handler instanceof ResourceHttpRequestHandler || isWelcomePage(handler);
    }

    private static boolean isWelcomePage(Object handler) {
        return handler instanceof ParameterizableViewController view && "forward:index.html".equals(view.getViewName());
    }

    private static boolean isPublic(Object handler) {
        return handler instanceof HandlerMethod method && method.hasMethodAnnotation(PublicEndpoint.class);
    }

    private static boolean reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
        return false;
    }
}
