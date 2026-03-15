package com.stationly.backend.client;

import com.stationly.backend.model.ArrivalPrediction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;

@Component
public class TflApiClient implements TflApi {

        private final WebClient webClient;
        private final TflRateLimiter rateLimiter;

        @Value("${tfl.app.key}")
        private String appKey;

        @Value("${tfl.arrival.prediction.count}")
        private int arrivalPredictionCount;

        @Value("${tfl.api.timeout}")
        private int apiTimeout;

        public TflApiClient(WebClient.Builder webClientBuilder, TflRateLimiter rateLimiter) {
                this.rateLimiter = rateLimiter;
                this.webClient = webClientBuilder
                                .baseUrl("https://api.tfl.gov.uk")
                                .codecs(configurer -> configurer
                                                .defaultCodecs()
                                                .maxInMemorySize(2 * 1024 * 1024)) // 2MB
                                .build();
        }



        public List<ArrivalPrediction> getArrivalsByMode(String mode) {
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Mode/{mode}/Arrivals")
                                                .queryParam("app_key", appKey)
                                                .queryParam("count", arrivalPredictionCount)
                                                .build(mode))
                                .retrieve()
                                .bodyToFlux(ArrivalPrediction.class)
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .collectList()
                                .block();
        }



        public List<Map<String, Object>> getLinesByMode(String mode) {
                rateLimiter.acquire();
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Line/Mode/{mode}")
                                                .queryParam("app_key", appKey)
                                                .build(mode))
                                .retrieve()
                                .bodyToMono(
                                                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                                                })
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .block();
        }

        public List<Map<String, Object>> getStopPointsByLine(String lineId) {
                rateLimiter.acquire();
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Line/{lineId}/StopPoints")
                                                .queryParam("app_key", appKey)
                                                .build(lineId))
                                .retrieve()
                                .bodyToMono(
                                                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                                                })
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .block();
        }

        public Map<String, Object> getLineRoute(String lineId) {
                rateLimiter.acquire();
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Line/{lineId}/Route")
                                                .queryParam("app_key", appKey)
                                                .build(lineId))
                                .retrieve()
                                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                                })
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .block();
        }

        public Map<String, Object> getRouteSequence(String lineId, String direction) {
                rateLimiter.acquire();
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Line/{lineId}/Route/Sequence/{direction}")
                                                .queryParam("app_key", appKey)
                                                .queryParam("excludeCrowding", true)
                                                .build(lineId, direction))
                                .retrieve()
                                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                                })
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .block();
        }

        public List<Map<String, Object>> getLineStatuses(String modes) {
                rateLimiter.acquire();
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/Line/Mode/{modes}/Status")
                                                .queryParam("app_key", appKey)
                                                .build(modes))
                                .retrieve()
                                .bodyToMono(
                                                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                                                })
                                .timeout(java.time.Duration.ofSeconds(apiTimeout))
                                .block();
        }
}
