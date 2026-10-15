package com.msvieira.aviation.config;

import com.msvieira.aviation.adapter.aviationweather.AviationWeatherProperties;
import com.msvieira.aviation.adapter.aviationweather.AviationWeatherStatusHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient aviationWeatherRestClient(
            final RestClient.Builder builder, final AviationWeatherProperties props) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());

        return builder.baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                .defaultStatusHandler(new AviationWeatherStatusHandler())
                .build();
    }
}
