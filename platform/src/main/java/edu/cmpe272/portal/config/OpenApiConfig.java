package edu.cmpe272.portal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI portalOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Patient Portal Messaging Platform API")
                        .version("0.1.0")
                        .description("Educational demonstration with synthetic data only. AI outputs are "
                                + "demonstration review flags, not validated clinical triage. Use POST "
                                + "/api/auth/login, then click Authorize and paste the token."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
