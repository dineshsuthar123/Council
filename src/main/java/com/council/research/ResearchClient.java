package com.council.research;

import java.util.List;

/**
 * External search implementation used by the research service.
 */
public interface ResearchClient {
    List<ResearchSource> search(List<String> queries, int maxResults);
}
