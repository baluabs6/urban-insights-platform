package com.urban.ai.config;

import com.urban.ai.tools.UrbanDataTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes {@link UrbanDataTools} to the Spring AI MCP server auto-configuration, which serves them over
 * SSE ({@code /sse} + {@code /mcp/message}). The server itself is OFF by default
 * ({@code spring.ai.mcp.server.enabled=${MCP_SERVER_ENABLED:false}}); these endpoints require the admin
 * API key (see ApiKeyAuthFilter).
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider urbanMcpTools(UrbanDataTools urbanDataTools) {
        return MethodToolCallbackProvider.builder().toolObjects(urbanDataTools).build();
    }
}
