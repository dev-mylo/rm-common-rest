package com.rm.process.rest.aop;

import com.rm.common.core.exception.RmCommonException;
import com.rm.common.core.model.ResultInfo;
import com.rm.common.core.util.JsonUtils;
import com.rm.process.rest.util.HttpLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.servlet.http.HttpServletRequest;

import static com.rm.common.core.exception.ServiceStatusCode.SUCCESS;
import static com.rm.common.core.util.ResultInfoUtil.setResultInfo;

/**
 * ResponseProcess 모듈은 basePackages에 명시된 패키지의 Controller에서 Return 되어진
 * CommonException 및 Value 들에 대하여 후처리 하는 모듈이다.
 *
 * 1. 넘어온 모든 요청은 ResultInfo로 Wrapping되어 Return 되어진다.
 *
 */

@Slf4j
@RestControllerAdvice(
        basePackages = {
                "com.rm.spring",
                "com.rm.service"
        })
public class ResponseProcess implements ResponseBodyAdvice<Object> {

    @ExceptionHandler(Exception.class)
    public Object handleException(HttpServletRequest request, Exception e) {

        //Object resultData = getAdditionalResultData();
        //Object resultData = getAdditionalResultData();
        if (e instanceof RmCommonException) {
            return setResultInfo((RmCommonException) e);
        } else {
            return setResultInfo(new RmCommonException(e));
        }

    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ResultInfo) {
            HttpLogger.writeObjectLog(body);
            return body;
        } else {
            ResultInfo resultInfo = setResultInfo(SUCCESS, body);
            HttpLogger.writeObjectLog(resultInfo);

            /* String으로 값이 넘어올 경우 MessageConverter가 String용으로 매칭되기때문에 리턴 객체를 String으로 변환 필요
             * Controller에서 String Type Return 할 경우 Json Type Return 명시를 해줘야한다.
             * StringHttpMessageConverter 는 produces 타입을 보고 변환시킴 */
            if (null == body) {
                return resultInfo;
            }
            if (body instanceof CharSequence) {
                return JsonUtils.toJson(resultInfo);
            }
            else {
                return resultInfo;
            }

        }
    }

}
