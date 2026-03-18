package io.schnappy.chat.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks permissions from gateway-provided X-User-Permissions header.
 * No JWT parsing, no Redis -- gateway handles token validation.
 */
@Aspect
@Component
public class PermissionInterceptor {

    @Around("@within(requirePermission)")
    public Object checkClassPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        var method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequirePermission methodAnnotation = method.getAnnotation(RequirePermission.class);
        Permission required = methodAnnotation != null ? methodAnnotation.value() : requirePermission.value();
        checkPermission(required);
        return joinPoint.proceed();
    }

    @Around("@annotation(requirePermission) && !@within(io.schnappy.chat.security.RequirePermission)")
    public Object checkMethodPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        checkPermission(requirePermission.value());
        return joinPoint.proceed();
    }

    private void checkPermission(Permission required) {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var user = (GatewayUser) attrs.getRequest().getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!user.hasPermission(required.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");
        }
    }
}
