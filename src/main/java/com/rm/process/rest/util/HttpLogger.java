package com.rm.process.rest.util;

import com.rm.common.core.exception.RmCommonException;
import com.rm.common.core.model.ResultInfo;
import com.rm.common.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.containsAny;
import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * HTTP 정보를 로깅하는 유틸리티
 *
 * 프론트와 협업하면서, 앱단에서 자신이 호출하는 헤더조차 모르고 호출하면서 협업에 어려움을 많이 겪어서 만드는 유틸리티
 *
 * 기본적인 INFO 레벨의 Slf4j 구현체(Log4j, Logback 등) 활성화 시 아래와 같은 정보를 표시함
 *  - 호출자의 IP 주소
 *  - 리퀘스트 메서드 (GET, POST, ...)
 *  - 리퀘스트 URI (http 프로토콜과 도메인 주소 뒤의 "실제 주소")
 *  - 리퀘스트 바디 (JSON 혹은 HTML FORM 바디 등)
 *  - Basic / Bearer 인증 정보 (토큰을 해석해서 보여줌)
 *  - @RestController 등을 사용할 경우 ResponseProcess AOP로 받아온 리스폰스 객체 JSON 값
 *  - RestController 등에서 Exception이 던져져서 AOP에서 처리되었을 경우 Exception에 대한 요약 정보
 *  - 필터단에서 리스폰스 OutputStream이 직접적으로 쓰였을 경우 리스폰스 바디 문자열
 *  - 요청에 대한 응답 시간 (time elapsed), 요청에 대한 응답 전문의 크기 (byte(s) sent)
 *
 * DEBUG 레벨의 Slf4j 퍼사드 활성화 시 아래와 같은 정보를 추가적으로 표시함
 *  - 스택 트레이스 전문 (INFO 레벨에선 com.hanteo 하위의 스택트레이스만 표기하여 디버깅을 용이하게 함)
 *
 * TRACE 레벨의 Slf4j 퍼사드 활성화 시 아래와 같은 정보를 추가적으로 표시함
 *  - 모든 리퀘스트 헤더
 *  - 모든 리스폰스 헤더
 *
 * 리퀘스트에 대한 로직 수행 중 Exception이 떨어져서 해당 필터에 캐치될 경우, ERROR 레벨로 에러 정보와 함께 리퀘스트를 로깅함
 *
 * 또한, 대용량, 느린 요청/응답의 경우 호출자 IP, 호출 URL, 해당 경고에 대한 수치와 함께 별도의 WARN 레벨 로그 생성
 *  - 대용량 리퀘스트 바디의 경우 바이트 수 표시
 *  - 대용량 리스폰스 바디의 경우 바이트 수 표시
 *  - 느린 리스폰스의 경우 리스폰스에 걸린 시간 표시
 * 이때, 요청에 대한 분석을 용이하게 하기 위해 리퀘스트 로그도 WARN 레벨로 표시됨
 * (수동으로 HttpLogUtils.setWarningFlag() 를 사용해 리퀘스트 로그를 WARN 레벨로 만들 수도 있음)
 *
 * 해당 HTTP 로깅 유틸리티로 디버깅을 편하게 할 수 있길 바라며...
 */
@Slf4j
public class HttpLogger {
    // 대용량, 느린 요청/응답에 대한 로그를 남기고, 경고 레벨 로깅을 하기 위한 상수
    private static final long LARGE_REQUEST_BODY_WARNING_SIZE = 1000*1000; // 약 1MB
    private static final long LARGE_RESPONSE_BODY_WARNING_SIZE = 1000*1000; // 약 1MB
    private static final long SLOW_RESPONSE_WARNING_MILLIS = 1000*3; // 3초

    // 내부적으로 로깅에 사용하는 Request Attribute 키값 상수
    public static final String REQUEST_START_TIME = "requestStartTime";
    public static final String RESPONSE_OBJECT = "responseObject";
    public static final String WARNING_INFO_LIST = "warningFlag";


    /**
     * 리퀘스트/리스폰스 로깅
     */
    public static void writeHttpLog(HttpServletRequest request, HttpServletResponse response) {
        int responseStatus = response.getStatus();
        Object responseObject = request.getAttribute(RESPONSE_OBJECT);

        boolean isImportantLog = HttpLogUtils.isCriticalException(responseObject) || HttpLogUtils.isImportantStatus(responseStatus);

        // 로깅을 원치 않는 리퀘스트 혹은 INFO 이상의 레벨이라면 로그 미기록
        // 로그레벨이 INFO 이상이더라도 중요 예외 혹은 Status Code 발생 시 로그
        if (HttpLogUtils.isExcludedRequest(request) || !(log.isInfoEnabled() || isImportantLog)) return;

        // 리퀘스트 로그를 먼저 뿌려줌
        StringBuilder result = writeHttpLog(request);

        long responseSize = -1;
        if (responseObject == null && response instanceof ContentCachingResponseWrapper) {
            // 받아온 바이트 수 기록
            responseSize = ((ContentCachingResponseWrapper) response).getContentSize();

            // JSON 데이터 혹은 키/벨류 폼 데이터일 경우에만 해당 내용 노출
            // 나머지는 API 서버에서 의미없는 데이터(text/plain 등) 혹은 읽기 힘든 데이터(이미지, 바이너리 등)일 가능성이 있으므로, 전송된 MIME 형식과 바이트 용량만 표시
            if (containsAny(response.getContentType(), "application/json", "application/x-www-form-urlencoded")) {
                // AOP 등으로 받아온 리스폰스 객체가 없을 경우(AOP 이전 필터단에서 에러가 뜰 경우) 리스폰스 바디 스트링값을 들고와서 뿌려줌
                String responseBody = new String(((ContentCachingResponseWrapper) response).getContentAsByteArray());
                if (!isEmpty(responseBody)) {
                    result.append("Response body with status ").append(responseStatus).append(": ").append(responseBody);
                }
            } else if (response.getContentType() != null) {
                // 컨텐츠 타입이 있을 경우 컨텐츠 타입 및 리스폰스 사이즈를 표시해줌
                result.append("Response body with status ").append(responseStatus).append(" and MIME type '").append(response.getContentType()).append("'");
            } else if (responseStatus > 0) {
                // 바디가 비어있을 경우 Status라도 보여줌 (0번, 미설정 제외)
                result.append("Response status ").append(responseStatus);
            } else {
                // 리스폰스에 대해 아무것도 알 수 없을 경우 알 수 없는 리스폰스라고 표시
                result.append("Unknown response, maybe chaining some additional filter?");
            }
        } else {
            // 예외 객체를 받았을 경우 예외의 정보를 잘 정돈해서 요약본으로 보여줌
            if (responseObject instanceof Exception) {
                // 먼저 예외의 메세지를 뿌려줌
                Exception exception = (Exception) responseObject;
                if (exception.getMessage() != null) {
                    result.append("Exception occurred: ").append(exception.getMessage()).append("\n");
                }

                // 보통은 CommonException으로 모든 예외가 처리되나, 간혹 아닐 경우엔 실제 일어난 예외 클래스명도 알려줌
                if (!(exception instanceof RmCommonException)) {
                    result.append("Exception type: ").append(exception.getClass().getCanonicalName()).append("\n");
                }

                // 예외가 생긴 원인(Cause)를 모두 추적해서 보여줌
                // "모든" 원인(Cause)을 추적하기 위해 while문을 사용했으며
                // Cause가 없는 경우 로깅하지 않기 위해 if (cause != null)을 do {} while(); 문 앞에 집어넣음
                Throwable cause = exception.getCause();
                if (cause != null) do {
                    result.append("Nested exception: ").append(cause.getMessage())
                          .append(" (").append(cause.getClass().getCanonicalName()).append(")").append("\n");
                } while ((cause = cause.getCause()) != null);

                // instanceof 연산자엔 null 체크도 들어가 있음
                // 따라서, Cause가 없는 독자적인 CommonException일 땐 자체적인 스택트레이스를 호출함(Cause가 없는 경우 exception.getCause()은 null을 반환함)
                // Cause가 있는 경우 CommonException이 아닌 Cause를 찾음 (Root cause엔 원하는 Stacktrace가 없을 수도 있으므로: ex. SQL 관련 에러, HCE가 아닌 첫번째 Cause를 찾음)
                // 메세지가 지저분해져서 사용하진 않겠지만, 혹시 CommonException이 여러겹 감싸져있는 구조라면 제일 마지막(Root) CommonException의 스택트레이스를 표시함
                Throwable exceptionToStackTrace = exception;
                while (exceptionToStackTrace.getCause() != null && exceptionToStackTrace instanceof RmCommonException) {
                    exceptionToStackTrace = exceptionToStackTrace.getCause();
                }

                // 스택트레이스 로그를 정리해서 보여줌
                // 보통 스프링 등 내부 로직엔 문제가 없는 경우가 많고, 사용자의 실수로 인한 에러가 많으므로 com.mylo 패키지 내의 스택트레이스만 보여줌
                int count = 0;
                for (StackTraceElement element : exceptionToStackTrace.getStackTrace()) {
                    count++;
                    if (count <= 1) {
                        // 첫 1줄의 스택트레이스까지는 무엇이든간에 표시함
                        result.append("\t").append(element).append("\n");
                    } else if (element.getClassName().startsWith("com.mylo") && !element.getClassName().contains("CGLib")
                    /* && !containsAny(element.getClassName(), "com.mylo.process.rest", "com.mylo.common.database")*/) {
                        // com.hanteo.process.rest 패키지는 StubBO 사용시 매우 빈번하게 호출되며, 잘 작성했다고 가정하고 INFO 레벨 로깅에서 제외함
                        // com.hanteo.database 패키지도 자주 사용되며, 잘 작성했다고 가정하고 INFO 레벨 로깅에서 제외함
                        result.append("\t").append(element).append("\n");
                    } else if (log.isDebugEnabled()) {
                        // 그래도 DEBUG 레벨까지 내려갈 경우 꽤 상세한 정보를 보여줌
                        result.append("\t").append(element).append("\n");
                    }
                }
            } else {
                // 리스폰스 바이트 기록을 위해 모든 상황에서 Json 파싱을 함 / TODO 혹시 다른 대안이 있다면 사용바람
                String responseJSON = JsonUtils.toJson(responseObject);
                responseSize = responseJSON.length();

                // DEBUG 아래의 레벨일 때만 리스폰스 바디 데이터를 보여줌
                if (log.isDebugEnabled()) {
                    // 일반적으로 AOP를 거친 RestController들은 객체를 반환하며, 그 객체의 JSON 스트링을 로깅함
                    result.append("Response JSON: ").append(responseJSON);
                } else if (responseObject instanceof ResultInfo) {
                    // 이외의 경우에도 ResultInfo의 코드는 리턴해줌
                    ResultInfo resultInfo = (ResultInfo) responseObject;
                    result.append("Response successfully with ResultInfo code ").append(resultInfo.getCode());
                }
            }
        }

        boolean hasRequestStartTime = request.getAttribute(REQUEST_START_TIME) instanceof Long;
        boolean hasResponseSize = responseSize > 0;
        if (hasRequestStartTime || hasResponseSize) {
            result.append(" (");
            // 리스폰스 시간 기록
            if (hasRequestStartTime) {
                long startTime = (long) request.getAttribute(REQUEST_START_TIME);
                long timeElapsed = System.currentTimeMillis() - startTime;

                result.append(String.format("%.2f", timeElapsed / 1000F)).append(" second(s) elapsed");

                // 리스폰스에 걸리는 시간이 길다면 API 자체에 무언가 문제가 있으므로 리퀘스트 로그를 WARN 레벨로 격상
                if (timeElapsed > SLOW_RESPONSE_WARNING_MILLIS) {
                    HttpLogUtils.setWarningInfo("Slow response");
                }
            }
            // 둘 다 기록해야한다면, 자연스러운 문장을 만들기 위해 쉼표를 넣어줌
            if (hasRequestStartTime && hasResponseSize) {
                result.append(", ");
            }
            // 리스폰스 크기 기록
            if (hasResponseSize) {
                result.append(String.format("%,d", responseSize)).append(" byte(s) sent");

                // 리스폰스 데이터로 대용량 전송을 한다면 API 자체에 무언가 문제가 있으므로 리퀘스트 로그를 WARN 레벨로 격상
                if (responseSize > LARGE_RESPONSE_BODY_WARNING_SIZE) {
                    HttpLogUtils.setWarningInfo("Large response body");
                }
            }
            result.append(")");
        }

        // 리스폰스 데이터가 필요할 경우도 있으므로, 해당 객체도 로깅하되 TRACE 레벨에서 로깅하도록 함
        // 평소엔 정말 필요없는 정보지만, 간혹 Response 헤더로 CORS 관련 헤더를 프론트에서 요구하기도 하므로
        // 필요한 경우 TRACE 레벨까지 내려서 디버깅할 수 있도록 함
        if (log.isTraceEnabled()) {
            List<String> headerKeys = new ArrayList<>(response.getHeaderNames());

            Map<String, String> headers = new HashMap<>();
            for (String key : headerKeys) {
                headers.put(key, response.getHeader(key));
            }

            for (String key : headers.keySet()) {
                result.append("\n\tResponse Header '").append(key).append("': '").append(headers.get(key)).append("'");
            }
        }

        // 경고 플래그를 담은 리스트
        Object warningInfoList = request.getAttribute(WARNING_INFO_LIST);

        // 크리티컬한 에러일때만 에러로그로, 경고 플래그가 세워진다면 WARN 로그로, 이외엔 INFO 로그로 생성
        if (HttpLogUtils.isCriticalException(responseObject)) {
            log.error("\n{}\n", result);
        } else if (warningInfoList != null) {
            // 로그의 마지막은 \n으로 끝나기에, 붙여서 WARNING을 씀
            log.warn("\n{}\nREQUEST/RESPONSE WARNING: {}\n", result, warningInfoList);
        } else {
            log.info("\n{}\n", result);
        }
    }

    /**
     * 리퀘스트 로깅
     */
    private static StringBuilder writeHttpLog(HttpServletRequest request) {
        StringBuilder result = new StringBuilder();

        Enumeration<String> headerKeys = request.getHeaderNames();

        // 보통 로드밸런서를 거치는 등 여러 서비스를 거치며 오기에 원본 IP를 알아내기 위해 헤더값을 조사함
        String ip = HttpLogUtils.getIpAddr(request);

        String method = request.getMethod();

        // 디버그/트레이스 모드일땐 도메인, 포트를 포함한 전체 URL, 아닌 경우 실제 path(URI)만 표시함
        String uri;
        if (log.isDebugEnabled()) {
            uri = request.getRequestURL().toString();
        } else {
            uri = request.getRequestURI();
        }

        // URI에 쿼리스트링을 가지고 있을 경우 함께 출력함
        if (!isEmpty(request.getQueryString())) {
            uri += "?"+request.getQueryString();
        }

        result.append("Requested URI '")
              .append(method).append(" ").append(uri)
              .append("'").append(", IP '").append(ip).append("'");



        result.append("\n");

        try {
            // 리퀘스트 바디에 대한 로깅
            if (request instanceof ContentCachingRequestWrapper) {
                // 리퀘스트 바디로 대용량의 무언가가 들어왔다면 문제가 있는 것으로 판명, 아이피 및 호출한 URI 기재
                if (request.getContentLength() > LARGE_REQUEST_BODY_WARNING_SIZE) {
                    HttpLogUtils.setWarningInfo("Large request body");
                }

                // JSON 데이터 혹은 키/벨류 폼 데이터일 경우에만 해당 내용 노출
                // 나머지는 API 서버에서 의미없는 데이터(text/plain 등) 혹은 읽기 힘든 데이터(이미지, 바이너리 등)일 가능성이 있으므로, 전송된 MIME 형식과 바이트 용량만 표시
                if (containsAny(request.getContentType(), "application/json", "application/x-www-form-urlencoded")) {
                    // 서블릿에서 바디를 한번이라도 호출했다면 getContentAsByteArray() 에 기록됨
                    byte[] bodyBytes = ((ContentCachingRequestWrapper) request).getContentAsByteArray();

                // 하지만 서블릿에서 바디를 한번도 호출하지 않았다면 (@RequestBody 미사용 등...)
                // 위에서 기록되지 않으므로, 수동으로 InputStream을 호출하여 로깅을 할 수 있도록 함
                if ((bodyBytes.length == 0) && request.getContentLength() > 0) {
                    bodyBytes = new byte[request.getContentLength()];

                        InputStream input = request.getInputStream();
                        for (int cursor = 0; cursor < request.getContentLength(); cursor++) {
                            bodyBytes[cursor] = (byte) input.read();
                        }
                        input.close();
                    }

                    // Byte 배열을 String으로 변환하여 읽어들인 뒤, 로깅 진행
                    String requestBody = new String(bodyBytes);
                    if (!isEmpty(requestBody)) {
                        result.append("Request body: ").append(requestBody).append("\n");
                    }
                } else if (request.getContentType() != null && request.getContentLength() > 0) { // contentType이 null인 경우는 리퀘스트 바디가 없는 경우임 (단순 GET 혹은 쿼리스트링만 존재하는 API 등)
                    result.append("Request body with MIME type '").append(request.getContentType())
                          .append("', ").append(String.format("%,d", request.getContentLength())).append(" byte(s) received").append("\n");
                }
            }
        } catch (IOException e) {
            result.append("Request body: 'ERROR DURING FETCH REQUEST BODY: ").append(e.getMessage()).append(" (").append(e.getClass().getCanonicalName()).append(")").append("'\n");
        }

        // 리퀘스트 헤더에 대한 로깅 (트레이스 레벨일때만 활성화)
        if (log.isTraceEnabled()) {
            Map<String, String> headers = new HashMap<>();
            while (headerKeys.hasMoreElements()) {
                String key = headerKeys.nextElement();
                headers.put(key, request.getHeader(key));
            }

            // 나머지 헤더들을 기록 (트레이스 레벨에서만 기록하도록 하여, debug 레벨 이상에서의 불필요한 로깅 방지)
            for (String key : headers.keySet()) {
                result.append("\tHeader '").append(key).append("': '").append(headers.get(key)).append("'\n");
            }
        }

        return result;
    }



    private static String getParameterListByInvocation(MethodInvocation invocation) {
        StringBuilder parameterBuilder = new StringBuilder();

        List<Class<?>> parameterTypes = Arrays.asList(invocation.getMethod().getParameterTypes());
        if (!parameterTypes.isEmpty()) {
            parameterBuilder.append(parameterTypes.get(0));
            for (int cursor = 1; cursor < parameterTypes.size(); cursor++) {
                parameterBuilder.append(",").append(parameterTypes.get(cursor));
            }
        }

        return parameterBuilder.toString();
    }



    /**
     * AOP로 받아온 Response 객체를 등록하는 메서드
     * 리스폰스 객체가 등록되면 해당 객체의 정보를 상세하게 뿌려줌
     *
     * 만약 등록되지 않더라도 로그엔 리스폰스 스트링값을 넘겨주게 됨
     *
     * @param responseObject AOP를 통한 리스폰스 직전에 넘겨주고자 했던 객체 (REST 통신에 한함)
     */
    public static void writeObjectLog(Object responseObject) {
        if (responseObject == null) return;

        ServletRequestAttributes requestAttributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) return;

        HttpServletRequest request = requestAttributes.getRequest();
        request.setAttribute(RESPONSE_OBJECT, responseObject);
    }
}
