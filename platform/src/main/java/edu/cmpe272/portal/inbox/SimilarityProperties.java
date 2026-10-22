package edu.cmpe272.portal.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param threshold minimum cosine similarity for a neighbour to be shown (tuned from seeded data) */
@ConfigurationProperties("app.similarity")
public record SimilarityProperties(double threshold) {
}
