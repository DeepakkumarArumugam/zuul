/**
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.zuul.context;

import com.netflix.zuul.message.Header;
import com.netflix.zuul.message.HeaderName;
import com.netflix.zuul.message.Headers;
import com.netflix.zuul.message.ZuulMessage;
import com.netflix.zuul.message.http.*;
import com.netflix.zuul.rx.UnicastDisposableCachingSubject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

/**
 * User: michaels@netflix.com
 * Date: 2/25/15
 * Time: 4:03 PM
 */
public class RxNettySessionContextFactory implements SessionContextFactory<HttpServerRequest, HttpServerResponse>
{
    private static final Logger LOG = LoggerFactory.getLogger(RxNettySessionContextFactory.class);

    @Override
    public ZuulMessage create(SessionContext context, HttpServerRequest httpServerRequest)
    {
        // Get the client IP (ignore XFF headers at this point, as that can be app specific).
        String clientIp = getIpAddress(httpServerRequest.getNettyChannel());

        // TODO - How to get uri scheme from the netty request?
        String scheme = "http";

        // This is the only way I found to get the port of the request with netty...
        int port = ((InetSocketAddress) httpServerRequest.getNettyChannel().localAddress()).getPort();
        String serverName = ((InetSocketAddress) httpServerRequest.getNettyChannel().localAddress()).getHostString();

        // Setup the req/resp message objects.
        HttpRequestMessage request = new HttpRequestMessageImpl(
                context,
                httpServerRequest.getHttpVersion().text(),
                httpServerRequest.getHttpMethod().name().toLowerCase(),
                httpServerRequest.getUri(),
                copyQueryParams(httpServerRequest),
                copyHeaders(httpServerRequest),
                clientIp,
                scheme,
                port,
                serverName
        );

        // Store this original request info for future reference (ie. for metrics and access logging purposes).
        request.storeInboundRequest();

        return wrapBody(request, httpServerRequest);
    }

    @Override
    public Observable<ZuulMessage> write(ZuulMessage msg, HttpServerResponse nativeResponse)
    {
        HttpResponseMessage zuulResp = (HttpResponseMessage) msg;

        // Set the response status code.
        nativeResponse.setStatus(HttpResponseStatus.valueOf(zuulResp.getStatus()));

        // Now set all of the response headers - note this is a multi-set in keeping with HTTP semantics
        for (Header entry : zuulResp.getHeaders().entries()) {
            nativeResponse.getHeaders().add(entry.getKey(), entry.getValue());
        }

        // Write response body stream as received.
        Observable<ZuulMessage> chain;
        Observable<ByteBuf> bodyStream = zuulResp.getBodyStream();
        if (bodyStream != null) {
            chain = bodyStream
                    .doOnNext(bb -> nativeResponse.writeBytesAndFlush(bb))
                    .ignoreElements()
                    .doOnCompleted(() -> nativeResponse.close())
                    .map(bb -> msg);
        }
        else {
            chain = Observable.just(msg);
        }
        return chain;
    }


    private ZuulMessage wrapBody(HttpRequestMessage request, HttpServerRequest<ByteBuf> nettyServerRequest)
    {
        //PublishSubject<ByteBuf> cachedContent = PublishSubject.create();
        UnicastDisposableCachingSubject<ByteBuf> cachedContent = UnicastDisposableCachingSubject.create();

        // Subscribe to the response-content observable (retaining the ByteBufS first).
        nettyServerRequest.getContent().map(ByteBuf::retain).subscribe(cachedContent);

        request.setBodyStream(cachedContent);

        return request;
    }

    private Headers copyHeaders(HttpServerRequest httpServerRequest)
    {
        Headers headers = new Headers();
        for (Map.Entry<String, String> entry : httpServerRequest.getHeaders().entries()) {
            HeaderName hn = HttpHeaderNames.get(entry.getKey());
            headers.add(hn, entry.getValue());
        }
        return headers;
    }

    private HttpQueryParams copyQueryParams(HttpServerRequest httpServerRequest)
    {
        HttpQueryParams queryParams = new HttpQueryParams();
        Map<String, List<String>> serverQueryParams = httpServerRequest.getQueryParameters();
        for (String key : serverQueryParams.keySet()) {
            for (String value : serverQueryParams.get(key)) {
                queryParams.add(key, value);
            }
        }
        return queryParams;
    }

    private static String getIpAddress(Channel channel) {
        if (null == channel) {
            return "";
        }

        SocketAddress localSocketAddress = channel.localAddress();
        if (null != localSocketAddress && InetSocketAddress.class.isAssignableFrom(localSocketAddress.getClass())) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) localSocketAddress;
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
        }

        SocketAddress remoteSocketAddr = channel.remoteAddress();
        if (null != remoteSocketAddr && InetSocketAddress.class.isAssignableFrom(remoteSocketAddr.getClass())) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteSocketAddr;
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
        }

        return null;
    }
}
