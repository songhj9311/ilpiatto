package com.ilpiatto.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 한 건마다 "IP | 메서드 주소 | 상태코드 | 처리시간" 한 줄을 남긴다.
 * 기록 위치는 application.properties 의 logging.file.name.
 */
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ilpiatto");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 배포 스크립트의 상태 확인(/java/health)은 기록하지 않음
        return request.getRequestURI().endsWith("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        // Nginx 뒤에 있으므로 실제 접속자 IP는 X-Real-IP 헤더에 있음
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            log.error("{} | {} {} | 500 | 처리 중 오류", ip, request.getMethod(), request.getRequestURI(), e);
            throw e;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        int status = response.getStatus();
        if (status >= 400) {
            log.warn("{} | {} {} | {} | {}ms", ip, request.getMethod(), request.getRequestURI(), status, ms);
        } else {
            log.info("{} | {} {} | {} | {}ms", ip, request.getMethod(), request.getRequestURI(), status, ms);
        }
    }
}
