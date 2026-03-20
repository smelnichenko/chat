package io.schnappy.chat.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionInterceptorTest {

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @InjectMocks
    private PermissionInterceptor permissionInterceptor;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // Helper annotation for tests
    @RequirePermission(Permission.CHAT)
    private static class AnnotatedClass {
        public void someMethod() {}
    }

    @Test
    void checkClassPermission_userHasPermission_proceeds() throws Throwable {
        var user = new GatewayUser(1L, "uuid", "test@test.com", List.of("CHAT"));
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = AnnotatedClass.class.getMethod("someMethod");
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn("result");

        Object result = permissionInterceptor.checkClassPermission(joinPoint, annotation);

        assertThat(result).isEqualTo("result");
        verify(joinPoint).proceed();
    }

    @Test
    void checkClassPermission_userLacksPermission_throwsForbidden() throws Throwable {
        var user = new GatewayUser(1L, "uuid", "test@test.com", List.of("METRICS"));
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = AnnotatedClass.class.getMethod("someMethod");
        when(methodSignature.getMethod()).thenReturn(method);

        assertThatThrownBy(() -> permissionInterceptor.checkClassPermission(joinPoint, annotation))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void checkClassPermission_noGatewayUser_throwsUnauthorized() throws Throwable {
        // No gatewayUser attribute set

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = AnnotatedClass.class.getMethod("someMethod");
        when(methodSignature.getMethod()).thenReturn(method);

        assertThatThrownBy(() -> permissionInterceptor.checkClassPermission(joinPoint, annotation))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void checkClassPermission_noRequestContext_throwsUnauthorized() throws Throwable {
        RequestContextHolder.resetRequestAttributes();

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = AnnotatedClass.class.getMethod("someMethod");
        when(methodSignature.getMethod()).thenReturn(method);

        assertThatThrownBy(() -> permissionInterceptor.checkClassPermission(joinPoint, annotation))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void checkMethodPermission_userHasPermission_proceeds() throws Throwable {
        var user = new GatewayUser(1L, "uuid", "test@test.com", List.of("CHAT"));
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = permissionInterceptor.checkMethodPermission(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void checkMethodPermission_userLacksPermission_throwsForbidden() {
        var user = new GatewayUser(1L, "uuid", "test@test.com", List.of("PLAY"));
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);

        RequirePermission annotation = AnnotatedClass.class.getAnnotation(RequirePermission.class);

        assertThatThrownBy(() -> permissionInterceptor.checkMethodPermission(joinPoint, annotation))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
