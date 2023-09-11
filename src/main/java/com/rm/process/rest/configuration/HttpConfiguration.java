package com.rm.process.rest.configuration;

import com.rm.process.rest.util.HttpLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.rm.process.rest.util.CorsUtils.setCors;

@Slf4j
public class HttpConfiguration {
    @Bean(name = "multiHttpFilter")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public OncePerRequestFilter multiHttpFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                request.setAttribute(HttpLogger.REQUEST_START_TIME, System.currentTimeMillis());

                setCors(request, response);

                // 로깅을 할 경우 리퀘스트/리스폰스 바디를 읽게 되는데, Stream 객체는 자체적으로 커서를 갖고 있으므로 1회용으로 읽는게 끝임
                // 해당 1회용 스트림을 다시 읽을 수 있도록 바디 데이터를 캐싱해주는 클래스를 사용함 (스프링에서 기본 제공되는 클래스)
                ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
                ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    // Preflight 요청일 경우 묻지도 따지지도 않고 200번 리턴
                    // 위에서 allowOrigin 설정이 안된 경우 필터 및 내부 로직을 태우지 않고 빠르게 CORS 정책을 어겼다는 사실을 통보해주기도 하는 용도
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    try {
                        chain.doFilter(wrappedRequest, wrappedResponse);

                        // 요청 성공 시 (AOP를 거칠 경우, ResultInfo 실패 코드 포함) Http 로그를 표시
                        // INFO 레벨 이상일 경우 로직을 덜 태우기 위해 if문 사용 (멀티쓰레드 환경에도 정확한 로깅을 위해 한번에 출력)
                        HttpLogger.writeHttpLog(wrappedRequest, wrappedResponse);
                    } catch (Exception e) {
                        // AOP를 거치지 못하고 필터단에서 Exception 발생 시 해당 Exception을 로깅
                        request.setAttribute(HttpLogger.RESPONSE_OBJECT, e);
                        // INFO 레벨 이상일 경우 로직을 덜 태우기 위해 if문 사용 (멀티쓰레드 환경에도 정확한 로깅을 위해 한번에 출력)
                        HttpLogger.writeHttpLog(wrappedRequest, wrappedResponse);

                        // Exception을 JSON 형태의 ResultInfo로 감싸서 수동으로 response 작성
                        //writeExceptionResponse(wrappedRequest, wrappedResponse, e);
                    } finally {
                        // 정상적으로 Response를 뿌려주기 위해 리스폰스 바디를 복사하여 API 호출자에게 보내줌
                        wrappedResponse.copyBodyToResponse();
                    }
                }
            }
        };
    }


}
