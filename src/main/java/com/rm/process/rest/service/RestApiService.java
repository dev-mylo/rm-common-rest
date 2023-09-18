package com.rm.process.rest.service;

import com.rm.common.core.exception.ErrorType;
import com.rm.common.core.exception.RmCommonException;
import com.rm.common.core.exception.ServiceStatusCode;
import com.rm.common.core.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

@Service
public class RestApiService<T> {
    private RestTemplate restTemplate;
    @Autowired
    public RestApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<T> get(String url, String token) {
        return getCallApiEndpoint(url, (Class<T>)Object.class, token);
    }
    public ResponseEntity<T> get(String url, MultiValueMap<String, String> params, String token) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url).queryParams(params);
        url = builder.build(false).toUriString();
        //Object params 벗겨내서 url 셋팅진행.
        return getCallApiEndpoint(url, (Class<T>)Object.class, token);
    }
    public Object post(String url, String contentsType, Object body, Class<T> clazz, String token) {
        Object resultData = getResponseResultData(callApiEndpoint(url, contentsType, HttpMethod.POST, body, clazz, token));
        return resultData;
    }
    private ResponseEntity<T> callApiEndpoint(String url, String contentsType, HttpMethod httpMethod, Object body, Class<T> clazz, String token) {
        HttpHeaders headers = getHttpHeaders(contentsType, token);
        return restTemplate.exchange(url, httpMethod, new HttpEntity<>( body, headers), clazz);
    }
    private ResponseEntity<T> getCallApiEndpoint(String url, Class<T> clazz, String token) {
        HttpHeaders headers = getHttpHeaders("json", token);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>( null, headers), clazz);
    }
    private Object getResponseResultData(ResponseEntity<T> response) {
        if (null == response.getBody()) {
            throw new RmCommonException(ErrorType.ERROR, ServiceStatusCode.ERROR_NETWORK, "response is null!!");
        }
        Map<String, Object> resultData = JsonUtils.toMap(response.getBody().toString());
        int code = (int)resultData.get("code");
        if (100 != code && 200 != code) {
            throw new RmCommonException((String) resultData.get("message"));
        }

        return resultData.get("data");

    }
    private HttpHeaders getHttpHeaders(String contentsType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("Pragma", "no-cache");
        if (!"NoToken".equals(token)) {
            headers.set("Authorization", "Bearer " + token);
        }
        switch (contentsType) {
            case "multipart":
                headers.setContentType(MULTIPART_FORM_DATA);
                break;
            case "json":
                headers.set("Content-Type", "application/json;charset=UTF-8");
                break;
            default:
                headers.set("Content-Type", "application/json;charset=UTF-8");
                break;
        }
        return headers;
    }
}
