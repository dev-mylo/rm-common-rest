package com.rm.process.rest.util;

import com.rm.common.core.exception.RmCommonException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.startsWithAny;

@Slf4j
public class HttpLogUtils {
    /**
     * 제외하고 싶은 리퀘스트가 있을 경우 해당 메서드에 등록함
     *
     * @param request 호출하는 쪽에서 실어오는 request, 해당 리퀘스트 데이터를 이용해서 제외하고 싶은 리퀘스트를 선별할 수 있도록 함
     * @return 해당 request를 로깅에서 제외할 지 말지 여부 (true를 리턴할 경우 제외)
     */
    public static boolean isExcludedRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        
        // 기본적으로 생존 여부를 알리는 헬스체크 URL인 "GET /"는 ELB 등 AWS 서비스에서 질리도록 호출하므로
        // 정작 필요한 로그를 놓칠 우려가 있음, 따라서 해당 URL의 요청은 로깅하지 않음
        if ("GET".equals(method) && "/".equals(uri)) return true;
        
        // 스웨거 관련 html에서의 비동기 호출이 굉장히 많으므로, 로그를 간소화하기 위해 해당 로그는 생략함
        if ("GET".equals(method) && startsWithAny(uri, "/webjars", "/swagger-resources", "/v2/api-docs", "/csrf")) return true;


        return false;
    }

    /**
     * 로그 레벨이 어떻든 간에 무조건 표시해야 할 예외인지 여부를 체크하는 메서드
     *
     * @param responseObject 리스폰스로 내보낼 객체
     * @return 크리티컬한 예외일 경우 true, 아닐 경우 false
     */
    public static boolean isCriticalException(Object responseObject) {
        if (responseObject instanceof RmCommonException) {
            RmCommonException hce = (RmCommonException) responseObject;

            // 코드 기반으로 중요도를 체크함
            int code = hce.getReason().getError();
            boolean isImportantCode =
                    code == 101 || code == 1000     // 101번 범용 실패 혹은 1000번 시스템 에러
                            || (200 <= code && code < 300)  // 200번대 쿼리 에러
                            || (700 <= code && code < 800); // 700번대 네트워크 관련 에러

            return isImportantCode;
        } else {
            // CommonException이 아니면서 예외인 경우엔 캐치되지 않은 예외, 로그에 띄울정도로 중요함
            return responseObject instanceof Throwable;
        }
    }

    /**
     * 로그 레벨이 어떻든 간에 무조건 표시해야 할 리스폰스 상태 코드인지 여부를 체크하는 메서드
     *
     * @param status 리스폰스로 내보낼 상태 코드
     * @return 크리티컬한 상태 코드일 경우 true, 아닐 경우 false
     */
    public static boolean isImportantStatus(int status) {
        // 100번 정보전달, 200번 성공, 300번 리다이렉션, 400번 잘못된 요청에 대해선 중요하지 않다고 표시
        // 500번 서버 에러부턴 문제가 있으므로 중요한 상태 코드라고 판단함
        return status >= 500;
    }

    /**
     * 리퀘스트의 호출자 IP를 로깅할 때 어떻게 로깅할건지에 대한 로직이 담긴 메서드
     * TODO 현재 X-Forwarded-For 헤더 및 HttpServletRequest.getRemoteAddr() 사용 중, 추가적인 분기로 다른 헤더를 요구하는 경우 수정해주길 바람
     *
     * @return 리퀘스트에 담긴 호출자의 IP
     */
    public static String getIpAddr(HttpServletRequest request) {
        // 보통 로드밸런서를 거치는 등 여러 서비스를 거치며 오기에 원본 IP를 알아내기 위해 헤더값을 조사함
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null) ip = request.getRemoteAddr();

        // IPv6 localhost 주소를 IPv4로 변환
        if ("0:0:0:0:0:0:0:1".equals(ip)) ip = "127.0.0.1";

        // IP가 두개 이상 기록된 경우 첫번째 IP를 가져옴 (두번째부터는 CF, ELB 등의 AWS 관련 주소일 가능성이 높음)
        if (ip.contains(",")) ip = ip.split(",")[0];

        return ip;
    }

    /**
     * 리퀘스트/리스폰스 로그를 WARN 레벨로 만들어주는 플래그를 활성화
     */
    public static void setWarningInfo(String warningInfo) {
        try {
            ServletRequestAttributes requestAttributes = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes());
            if (requestAttributes != null) {
                setWarningInfo(warningInfo, requestAttributes.getRequest());
            } else {
                throw new RmCommonException();
            }
        } catch (Exception e) {
            // Warning flag를 설정하지 못하는 문제는 있으면 안되므로, 풀 스택트레이싱과 함께 예외를 출력함
            log.error("Failed to set warning flag", e);
        }
    }
    /**
     * 리퀘스트/리스폰스 로그를 WARN 레벨로 만들어주는 플래그를 활성화
     */

    public static void setWarningInfo(String warningInfo, HttpServletRequest request) {
        List<String> warningInfoList;
        if (request.getAttribute(HttpLogger.WARNING_INFO_LIST) instanceof List) {
            /* 리퀘스트 어트리뷰트는 기본적으로 최상위 클래스인 Object 형태로 저장하고 내보내줌
             * 해당 어트리뷰트의 사용처에선 모두 List<String>으로 저장한다는 보장 하에 강제 형변환시켜줌 */
            @SuppressWarnings("unchecked")
            List<String> uncheckedObject = (List<String>) request.getAttribute(HttpLogger.WARNING_INFO_LIST);

            warningInfoList = uncheckedObject;
        } else {
            warningInfoList = new ArrayList<>();
        }

        warningInfoList.add(warningInfo);
        request.setAttribute(HttpLogger.WARNING_INFO_LIST, warningInfoList);
    }
}
