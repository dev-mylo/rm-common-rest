package com.rm.process.rest.util;

import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * 전반적인 CORS 정책 허용/불허와 관련된 작업을 도와주는 유틸리티
 */
public class CorsUtils {
    // 개발을 위해 로컬 HTML 파일을 직접 열거나 localhost 루프백 주소로 접근을 허용할지에 대한 부울 플래그값
    // 기본적으로 CorsConfiguration에서 스프링 프로퍼티를 활용하여 조작함 (프로필 real 여부, 프로퍼티 활성화 여부)
    @Setter private static boolean develop = false;

    // CORS 정책을 허용할 도메인에 대한 리스트, Origin으로 받아온 주소 중 도메인 부분(스킴, 포트 제외) 중 endsWith에 부합하는 경우 허용함
    @Setter private static String[] allowDomainList = {
            "cliveworks-api.com"
            , "cliveworks.co.kr"
            , "cliveworks-admin.co.kr"
    };

    public static void setCors(HttpServletRequest request, HttpServletResponse response) {
        // allowOrigin이 설정되지 않은 경우엔 CORS 관련 리스폰스 헤더들을 보내지 않고, CORS 정책을 어겼다고 판단함
        String allowOrigin = getAllowOrigin(request);
        if (allowOrigin != null) {
            String allowMethods = getAllowMethods(request);
            String allowHeaders = getAllowHeaders(request);

            // CORS 정책의 기본: 리퀘스트에서 보낸 Origin이 서버에서 허용하는 Origin이라면
            // 서버에서 Access-Control-Allow-Origin에 해당 Origin이 등록되어 있어야 함
            // 만약 그렇지 않으면 요청에 대한 응답을 받은 브라우저에서 CORS 정책 위반 에러를 띄우게 됨
            response.setHeader("Access-Control-Allow-Origin", allowOrigin);

            // 브라우저 내부에서 요청 시 credentials 옵션이 include 상태일 경우엔 응답 헤더에 반드시 Access-Control-Allow-Credentials가 true로 반환되어야 함
            // 하지만 브라우저 요청의 credentials 옵션이 활성화된 여부를 헤더로 보내지 않으니, 일단 무조건 활성화하고 보도록 함
            // 문제는 이 옵션이 활성화되면 Access-Control-Allow-Origin에 와일드카드(*)를 사용할 수 없으므로, allowOrigin에 와일드카드를 넣지 않음
            response.setHeader("Access-Control-Allow-Credentials", "true");

            // 요청한 메서드를 허용하는 리스폰스 헤더(Access-Control-Allow-Methods)를 내보내줘야 함
            response.setHeader("Access-Control-Allow-Methods", allowMethods);

            // 명시적으로 요청한 헤더(Access-Control-Request-Headers)가 있다면
            // Access-Control-Request-Header에서 요청한 헤더 리스트를 허용하는 리스폰스 헤더(Access-Control-Allow-Headers)를 내보내줘야 함
            if (allowHeaders != null) response.setHeader("Access-Control-Allow-Headers", allowHeaders);

            // Preflight 요청이고 허용된 오리진 리스트에 속한다면 브라우저에게 Preflight 요청에 대한 캐싱하도록 하여
            // 브라우저가 과도하게 Preflight 요청 및 서버에 부하를 주는 것을 방지함
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                // 해당 Preflight 요청이 캐싱되는 생명 주기를 설정함 (캐시 만료 시간)
                // 참고: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Max-Age
                response.setHeader("Access-Control-Max-Age", "3600");
            }
        }
    }

    /**
     * Access-Control-Allow-Origin에 보낼 오리진에 대한 설정
     * 기본적으로 ALLOW_ORIGIN_LIST에 포함되어 있다면 해당 Origin을 그대로 허용
     * IS_DEVELOP(개발 중) 일 땐, 로컬호스트 루프백을 포함한 개발중에도 사용될 법한 Origin도 허용
     *
     * @return 허용된다면 요청에 포함된 "Origin" 헤더를 그대로 반환, 아니라면 null 반환
     */
    public static String getAllowOrigin(HttpServletRequest request) {
        String requestOrigin = request.getHeader("Origin");
        if (requestOrigin == null) requestOrigin = "null";

        // 스킴, 포트 및 www. 부분을 제외한 도메인을 받아옴
        String requestDomain = requestOrigin.replaceAll("^(?:https?://)?(?:www\\.)?([^:/]+)(?::\\d+)?(?:/.*)?$", "$1");

        if (endsWithAny(requestDomain, allowDomainList)) {
            // 도메인 끝부분이 ALLOW_DOMAIN_LIST의 도메인 중 하나랑 일치하면 오리진 허용
            return requestOrigin;
        } else if (develop) {
            // 개발 중 옵션이 켜져있다면 리퀘스트 도메인이 "null"로 설정되거나, 루프백 주소일 경우 허용
            if ("null".equals(requestDomain) || equalsAny(requestDomain, "localhost", "127.0.0.1")) {
                return requestOrigin;
            }

            // 개발 중 옵션이 켜져있다면 리퀘스트 도메인이 iptime 공유기 서브넷 혹은 AWS Client VPN 서브넷에 해당될 때도 허용
            if (startsWithAny(requestDomain, "192.168", "172.21")) {
                return requestOrigin;
            }
        }

        return null;
    }

    /**
     * Access-Control-Allow-Methods에 보낼 메서드에 대한 설정
     * OPTIONS(Preflight) 요청에서는 Access-Control-Request-Method에 다음 본 요청에서 사용할 메서드를 전달해줌
     * 본 요청으로 바로 CORS 정책 검사를 하기도 하므로, 상황별로 CORS 정책을 허용할 메서드를 지정해주는 로직
     *
     * @return 허용할 메서드, 절대 Null 값을 반환하지 않음
     */
    public static String getAllowMethods(HttpServletRequest request) {
        String requestMethod = request.getHeader("Access-Control-Request-Method");
        if (isNotBlank(requestMethod)) {
            // Preflight 요청으로 CORS 정책 검사를 할 경우 리퀘스트 헤더로 실려온 메서드를 허용해야 함
            return requestMethod.toUpperCase();
        } else {
            // 본 요청으로 바로 CORS 정책 검사를 할 경우 요청할때 보냈던 메서드를 허용해야 함
            return request.getMethod().toUpperCase();
        }
    }

    /**
     * Access-Control-Allow-Headers에 보낼 헤더에 대한 설정
     * OPTIONS(Preflight) 요청에서는 Access-Control-Request-Headers에 다음 본 요청에서 사용할 헤더들을 전달해줌
     * 본 요청을 보낼 때에도 요청한 헤더(Access-Control-Request-Headers)가 있다면 CORS 정책을 허용할 헤더를 지정해주는 로직
     *
     * @return 허용할 헤더, 명시적으로 요청한 헤더(Access-Control-Request-Headers)가 없다면 null 반환
     */
    public static String getAllowHeaders(HttpServletRequest request) {
        String requestHeader = request.getHeader("Access-Control-Request-Headers");
        if (isNotBlank(requestHeader)) {
            return requestHeader;
        } else {
            return null;
        }
    }
}
