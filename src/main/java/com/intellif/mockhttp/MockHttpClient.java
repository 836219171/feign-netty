package com.intellif.mockhttp;

import com.intellif.feign.transfer.TransferRequest;
import com.intellif.feign.transfer.TransferResponse;
import org.apache.commons.lang.StringUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 模拟对springMVC的http调用
 *
 * @author inori
 * @create 2019-07-08 14:14
 */
public class MockHttpClient {

    private DispatcherServletProxy dispatcherServletProxy;

    public MockHttpClient(DispatcherServlet dispatcherServlet) {
        if (dispatcherServlet instanceof DispatcherServletProxy) {
            this.dispatcherServletProxy = (DispatcherServletProxy) dispatcherServlet;
        }
    }

    /**
     * 调用dispatcherServlet执行request请求
     *
     * @param request 请求
     * @return 基于传输的响应
     * @throws Exception 任何异常
     */
    public TransferResponse execute(TransferRequest request) throws Exception {
        MockHttpServletRequest mockReq = toMockHttpRequest(request);
        MockHttpServletResponse mockRes = new MockHttpServletResponse();
        dispatcherServletProxy.doProcessRequest(mockReq, mockRes);
        Map<String, Collection<String>> headers = new HashMap<>();
        for (String name : mockRes.getHeaderNames()) {
            headers.put(name, mockRes.getHeaders(name));
        }
        //构建用于netty传输的Response
        return new TransferResponse(request.getUuid(), mockRes.getStatus(), mockRes.getErrorMessage(), headers, mockRes.getContentAsByteArray(), request);
    }

    private MockHttpServletRequest toMockHttpRequest(TransferRequest request) {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setContent(request.getBody());
        //填充所有的header
        for (Map.Entry<String, Collection<String>> entry : request.getHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                mockRequest.addHeader(entry.getKey(), value);
            }
        }
        URI uri = URI.create(request.getUrl());
        mockRequest.setRequestURI(uri.getPath());
        mockRequest.setServletPath(uri.getPath());
        mockRequest.setQueryString(uri.getQuery());
        if (!StringUtils.isBlank(uri.getQuery())) {
            String[] parameters = uri.getQuery().split("&");
            for (String parameter : parameters) {
                if (StringUtils.isBlank(parameter)) {
                    continue;
                }
                String[] param = parameter.split("=");
                String key = param[0];
                String value = "";
                if (param.length >= 2) {
                    StringBuilder builder = new StringBuilder();
                    for (int i = 1; i < param.length; i++) {
                        builder.append(param[i].trim()).append(",");
                    }
                    value = builder.substring(0, builder.lastIndexOf(","));
                }
                mockRequest.setParameter(key, value);
            }
        }
        mockRequest.setPathInfo(uri.getQuery());
        mockRequest.setMethod(request.getMethod());
        return mockRequest;
    }
}