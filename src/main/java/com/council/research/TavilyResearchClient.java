package com.council.research;

import com.council.config.CouncilProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily-backed search client. It is inert unless a Tavily API key is configured.
 */
@Component
public class TavilyResearchClient implements ResearchClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyResearchClient.class);

    private final CouncilProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public TavilyResearchClient(CouncilProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getResearch().getTimeoutSeconds())))
                .build();
    }

    @Override
    public List<ResearchSource> search(List<String> queries, int maxResults) {
        CouncilProperties.ResearchConfig config = properties.getResearch();
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TAVILY_API_KEY is not configured");
        }

        Map<String, ResearchSource> byUrl = new LinkedHashMap<>();
        int perQueryLimit = Math.max(1, Math.min(maxResults, config.getMaxResults()));
        for (String query : queries == null ? List.<String>of() : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            for (ResearchSource source : searchOne(query, apiKey, perQueryLimit)) {
                byUrl.putIfAbsent(source.url(), source);
                if (byUrl.size() >= maxResults) {
                    return renumber(byUrl.values().stream().toList());
                }
            }
        }
        return renumber(byUrl.values().stream().toList());
    }

    private List<ResearchSource> searchOne(String query, String apiKey, int maxResults) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "search_depth", "basic",
                    "include_answer", false,
                    "include_raw_content", false,
                    "max_results", maxResults
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getResearch().getBaseUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getResearch().getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("research provider returned HTTP " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("results");
            List<ResearchSource> sources = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String url = result.path("url").asText("");
                    if (url.isBlank()) {
                        continue;
                    }
                    sources.add(new ResearchSource(
                            "",
                            result.path("title").asText(url),
                            url,
                            domainOf(url),
                            trim(result.path("content").asText(""), properties.getResearch().getMaxSnippetChars()),
                            result.path("published_date").asText(null),
                            result.path("score").asDouble(0.0)
                    ));
                }
            }
            return sources;
        } catch (Exception e) {
            log.warn("[research] Search failed for query '{}': {}", query, e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private List<ResearchSource> renumber(List<ResearchSource> sources) {
        List<ResearchSource> numbered = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            ResearchSource source = sources.get(i);
            numbered.add(new ResearchSource(
                    "S" + (i + 1),
                    source.title(),
                    source.url(),
                    source.domain(),
                    source.snippet(),
                    source.publishedAt(),
                    source.score()
            ));
        }
        return List.copyOf(numbered);
    }

    private String domainOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int max = Math.max(120, maxChars);
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max - 3) + "...";
    }
}
