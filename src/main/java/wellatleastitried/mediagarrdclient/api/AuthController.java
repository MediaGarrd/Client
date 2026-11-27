package wellatleastitried.mediagarrdclient.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import wellatleastitried.mediagarrdclient.dto.AuthStatusDto;
import wellatleastitried.mediagarrdclient.dto.PasswordRequest;
import wellatleastitried.mediagarrdclient.service.LoginAttempts;
import wellatleastitried.mediagarrdclient.service.PasswordService;
import wellatleastitried.mediagarrdclient.service.SessionService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);

    private static final int HASH_SLOTS = 3;

    private final Semaphore hashSlots = new Semaphore(HASH_SLOTS);
    private final PasswordService passwords;
    private final SessionService sessions;
    private final LoginAttempts attempts;

    public AuthController(PasswordService passwords, SessionService sessions, LoginAttempts attempts) {
        this.passwords = passwords;
        this.sessions = sessions;
        this.attempts = attempts;
    }

    @PublicEndpoint
    @GetMapping("/status")
    public AuthStatusDto status(HttpServletRequest request) {
        return new AuthStatusDto(passwords.isConfigured(), passwords.isFromEnvironment(), sessions.validate(SessionGate.sessionToken(request)));
    }

    @PublicEndpoint
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody PasswordRequest body, HttpServletRequest request) {
        if (!passwords.isConfigured()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "No password is set yet"));
        }
        String source = request.getRemoteAddr();
        var locked = attempts.tryBegin(source);
        if (locked.isPresent()) {
            return tooMany(source, locked.get());
        }
        if (!hashSlots.tryAcquire()) {
            attempts.abandon(source);
            return tooMany(source, LoginAttempts.BUSY_RETRY);
        }
        boolean recorded = false;
        try {
            boolean matched;
            try {
                matched = passwords.matches(body.password());
            } finally {
                hashSlots.release();
            }
            if (!matched) {
                attempts.failed(source);
                recorded = true;
                LOGGER.warn("Failed dashboard login from {}", source);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Incorrect password"));
            }
            attempts.succeeded(source);
            recorded = true;
            return signedIn(request);
        } finally {
            if (!recorded) {
                attempts.abandon(source);
            }
        }
    }

    private static ResponseEntity<?> tooMany(String source, Duration wait) {
        long seconds = Math.max(1, wait.plusMillis(999).toSeconds());
        LOGGER.warn("Login from {} refused, try again in {} second(s)", source, seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(Map.of("error", "Too many login attempts, try again in " + seconds + " seconds"));
    }

    @PublicEndpoint
    @PostMapping("/set-password")
    public ResponseEntity<?> setPassword(@RequestBody PasswordRequest body, HttpServletRequest request) {
        passwords.setInitial(body.password());
        sessions.clear();
        LOGGER.info("Dashboard password set from {}", request.getRemoteAddr());
        return signedIn(request);
    }

    @PublicEndpoint
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.invalidate(SessionGate.sessionToken(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request).toString()).build();
    }

    private ResponseEntity<?> signedIn(HttpServletRequest request) {
        String token = sessions.create();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie(token, null, request).toString())
            .body(Map.of("authenticated", true));
    }

    private static ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest request) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(SessionGate.COOKIE, value)
            .httpOnly(true)
            .secure(isSecure(request))
            .sameSite("Strict")
            .path("/");
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }

    static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if (forwarded == null) {
            return false;
        }
        int comma = forwarded.indexOf(',');
        return "https".equalsIgnoreCase((comma < 0 ? forwarded : forwarded.substring(0, comma)).trim());
    }
}
