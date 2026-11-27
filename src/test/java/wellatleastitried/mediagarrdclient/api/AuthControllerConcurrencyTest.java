package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import wellatleastitried.mediagarrdclient.dto.PasswordRequest;
import wellatleastitried.mediagarrdclient.service.LoginAttempts;
import wellatleastitried.mediagarrdclient.service.PasswordService;
import wellatleastitried.mediagarrdclient.service.SessionService;

@Timeout(30)
class AuthControllerConcurrencyTest {

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(address);
        return request;
    }

    @Test
    void parallelWrongLoginsFromOneSourceReachThePasswordCheckAtMostOnce() throws Exception {
        PasswordService passwords = mock(PasswordService.class);
        when(passwords.isConfigured()).thenReturn(true);
        AtomicInteger checks = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(passwords.matches(any())).thenAnswer(call -> {
            checks.incrementAndGet();
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return false;
        });
        AuthController controller = new AuthController(passwords, new SessionService(), new LoginAttempts());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        CountDownLatch refused = new CountDownLatch(7);
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                int status = controller.login(new PasswordRequest("guess"), from("10.2.0.1")).getStatusCode().value();
                if (status == 429) {
                    refused.countDown();
                }
                return status;
            }));
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        assertTrue(refused.await(10, TimeUnit.SECONDS));
        release.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(1, checks.get());
        assertEquals(1, statuses.stream().filter(status -> status == 401).count());
        assertEquals(7, statuses.stream().filter(status -> status == 429).count());
    }

    @Test
    void onlyAFewPasswordChecksRunAtOnceAcrossSources() throws Exception {
        PasswordService passwords = mock(PasswordService.class);
        when(passwords.isConfigured()).thenReturn(true);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        when(passwords.matches(any())).thenAnswer(call -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            running.decrementAndGet();
            return false;
        });
        AuthController controller = new AuthController(passwords, new SessionService(), new LoginAttempts());
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        CountDownLatch refused = new CountDownLatch(7);
        for (int i = 0; i < 10; i++) {
            String source = "10.3.0." + i;
            results.add(pool.submit(() -> {
                int status = controller.login(new PasswordRequest("guess"), from(source)).getStatusCode().value();
                if (status == 429) {
                    refused.countDown();
                }
                return status;
            }));
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(refused.await(10, TimeUnit.SECONDS));
        release.countDown();
        long refusedCount = 0;
        for (Future<Integer> result : results) {
            if (result.get(10, TimeUnit.SECONDS) == 429) {
                refusedCount++;
            }
        }
        pool.shutdownNow();

        assertEquals(3, peak.get());
        assertEquals(7, refusedCount);
    }

    @Test
    void anErrorFromThePasswordCheckReleasesTheSourceSoItCanTryAgain() {
        PasswordService passwords = mock(PasswordService.class);
        when(passwords.isConfigured()).thenReturn(true);
        when(passwords.matches(any())).thenThrow(new StackOverflowError()).thenReturn(true);
        AuthController controller = new AuthController(passwords, new SessionService(), new LoginAttempts());

        assertThrows(StackOverflowError.class, () -> controller.login(new PasswordRequest("guess"), from("10.4.0.1")));

        assertEquals(200, controller.login(new PasswordRequest("guess"), from("10.4.0.1")).getStatusCode().value());
    }

    @Test
    void aFinishedLoginDoesNotReleaseTheReservationOfTheNextAttemptFromTheSameSource() {
        PasswordService passwords = mock(PasswordService.class);
        when(passwords.isConfigured()).thenReturn(true);
        when(passwords.matches(any())).thenReturn(true);
        LoginAttempts attempts = new LoginAttempts();
        SessionService sessions = mock(SessionService.class);
        when(sessions.create()).thenAnswer(call -> {
            assertTrue(attempts.tryBegin("10.4.0.2").isEmpty());
            return "token";
        });
        AuthController controller = new AuthController(passwords, sessions, attempts);

        assertEquals(200, controller.login(new PasswordRequest("guess"), from("10.4.0.2")).getStatusCode().value());

        assertTrue(attempts.tryBegin("10.4.0.2").isPresent());
    }
}
