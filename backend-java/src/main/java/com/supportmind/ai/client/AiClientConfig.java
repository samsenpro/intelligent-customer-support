package com.supportmind.ai.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class AiClientConfig {

    /** Cabecera con la clave interna que exige el servicio de IA. */
    public static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /**
     * RestClient dedicado al servicio de IA:
     * <ul>
     *   <li>JSON en snake_case (convención de Python) con un ObjectMapper propio: no afecta a la API REST.</li>
     *   <li>Timeout de conexión corto; el tiempo total de cada llamada lo limita Resilience4j (TimeLimiter).</li>
     *   <li>HTTP/1.1 forzado: el cliente del JDK intenta un upgrade a h2c que uvicorn no soporta.</li>
     * </ul>
     */
    @Bean
    RestClient aiRestClient(RestClient.Builder builder, AiServiceProperties properties,
                            Jackson2ObjectMapperBuilder jacksonBuilder) {
        ObjectMapper snakeCase = jacksonBuilder.build()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // Tiempo máximo hasta recibir las cabeceras de la respuesta (en streaming, hasta el primer evento)
        requestFactory.setReadTimeout(properties.streamTimeout());
        return builder
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(snakeCase));
                })
                .build();
    }
}
