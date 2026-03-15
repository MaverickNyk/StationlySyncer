package com.stationly.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// @Configuration
public class OpenApiConfig {

        @Bean
        public OpenAPI stationlyOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Stationly API documentation")
                                                .description(
                                                                "### Welcome to the Stationly API Documentation\n\n" +
                                                                                "Stationly provides a high-performance middleware for transport data, specializing in TfL (Transport for London) integration. "
                                                                                +
                                                                                "Our API offers real-time arrival predictions, station metadata, and live line status updates across multiple transport modes.\n\n"
                                                                                +
                                                                                "#### Key Features:\n" +
                                                                                "- **Real-time Predictions**: Accurate arrival times for Tube, Overground, DLR, and more.\n"
                                                                                +
                                                                                "- **Station Metadata**: Detailed information about stations including coordinates and available modes.\n"
                                                                                +
                                                                                "- **Line Status**: Live updates on delays, closures, and service changes.\n"
                                                                                +
                                                                                "- **Cached Performance**: Optimized data retrieval using Redis and Firebase.\n\n"
                                                                                +
                                                                                "#### Usage Information:\n" +
                                                                                "All requests should include appropriate authentication where required. For high-volume access, please contact our support team.\n\n"
                                                                                +
                                                                                "Owned and maintained by **Stationly Limited**.")
                                                .version("v1.0.0")
                                                .contact(new Contact()
                                                                .name("Stationly Limited")
                                                                .url("https://stationly.co.uk")
                                                                .email("support@stationly.co.uk"))
                                                .license(new License()
                                                                .name("Apache 2.0")
                                                                .url("http://springdoc.org")))
                                .servers(List.of(
                                                new Server().url("https://api.stationly.co.uk/StationlyBE")
                                                                .description("Production Server (HTTPS)"),
                                                new Server().url("http://localhost:8080/StationlyBE")
                                                                .description("Local Development (HTTP)")));
        }
}