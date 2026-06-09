package com.council.judge;

import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CouncilHardQuestionCalibrationTest {

    @Test
    @DisplayName("Twenty hard questions do not overrate known unsafe answer patterns")
    void twentyHardQuestionsDoNotOverrateUnsafeAnswerPatterns() {
        List<ProductionCase> productionCases = List.of(
                payment("wallet duplicate retry", """
                        Wallet transfer with Redis, PostgreSQL, and Kafka. If idempotency key exists, return failure.
                        Begin transaction, debit U1, credit U2, commit, then publish Kafka event.
                        """, 0.50),
                payment("wallet idempotency stored late", """
                        Payment transfer algorithm: begin transaction, debit source wallet, credit destination wallet,
                        commit, then store idempotency record in PostgreSQL and cache it in Redis.
                        """, 0.55),
                payment("wallet kafka before commit", """
                        Wallet transfer with idempotency key. Begin transaction, debit U1, emit Kafka event for debit,
                        credit U2, emit Kafka event for credit, then commit PostgreSQL transaction.
                        """, 0.60),
                payment("wallet redis authority", """
                        Redis is the source of truth for wallet transfer idempotency. If Redis says the key is missing,
                        perform debit and credit in PostgreSQL and send Kafka events.
                        """, 0.60),
                payment("wallet no transaction", """
                        Payment transfer: check idempotency, debit U1 balance, call Kafka, then credit U2 balance.
                        Monitor failures and retry when needed.
                        """, 0.45),
                payment("wallet async-only fraud", """
                        Wallet payment transfer with idempotency and PostgreSQL transaction. Fraud detection should
                        never block synchronously; debit and credit first, then let Kafka consumers decide later.
                        """, 0.70),
                payment("wallet no outbox", """
                        Wallet transfer with idempotency and PostgreSQL transaction. Debit U1 and credit U2 atomically,
                        commit, then publish Kafka event directly from the request thread.
                        """, 0.72),
                payment("wallet vague architecture", """
                        Use idempotency, Redis, PostgreSQL transactions, Kafka events, locks, fraud checks, and metrics
                        for a wallet transfer. The exact order depends on implementation details.
                        """, 0.78),
                payment("wallet weak lock handling", """
                        Wallet payment transfer: insert idempotency record, begin transaction, debit and credit, insert
                        outbox event, commit. Use optimistic retries but no row locks or deterministic lock ordering.
                        """, 0.78),
                payment("wallet weak crash recovery", """
                        Wallet payment transfer: insert idempotency key, debit and credit in one transaction, insert
                        outbox event, commit. Retries are generally safe, but crash recovery is not discussed.
                        """, 0.78),
                redirect("url shortener ttl only", """
                        For the deleted short URL, Redis TTL and expiration prevent stale redirects. Query a replica
                        and send Kafka analytics after redirecting.
                        """, 0.65),
                redirect("url shortener unsafe replica", """
                        For the deleted short URL, return 404 after checking Redis. Query PostgreSQL primary or a read
                        replica depending on configured consistency and publish analytics to Kafka.
                        """, 0.55),
                redirect("url shortener stale lease", """
                        For the deleted short URL, use Redis cache-aside lease and return a cached lease response that
                        may be stale while PostgreSQL and Kafka catch up.
                        """, 0.55),
                redirect("url shortener invalidation only", """
                        For the deleted short URL, prevent stale redirects by sending an invalidation request to Redis.
                        Kafka analytics lag is monitored.
                        """, 0.70),
                redirect("url shortener missing pseudocode", """
                        For the deleted short URL, use tombstones, primary reads, singleflight, Kafka lag monitoring,
                        and tradeoffs. A concrete pseudocode algorithm is provided below.
                        """, 0.70),
                redirect("url shortener active cache first", """
                        Return 404/410 for the deleted URL, use deleted_at tombstone, primary reads, singleflight,
                        and separate Kafka analytics. Pseudocode: cachedRedirect = redis.get(alias);
                        if (cachedRedirect && !isTombstoned(redis, alias)) return redirect(cachedRedirect.url);
                        if (redis.get(alias) == DELETED) return 404;
                        """, 0.85),
                redirect("url shortener shallow tradeoff", """
                        Return 404 because deleted URL must not redirect. Use tombstone, primary reads, singleflight,
                        and Kafka lag metrics. The tradeoff is to prioritize redirect correctness.
                        Pseudocode: if (redis.get(alias) == DELETED) return 404; row = primaryDb.findByAlias(alias);
                        """, 0.88),
                redirect("url shortener no tombstone", """
                        Return 404 for deleted short URL. Use primary reads because replicas lag, singleflight for
                        Redis degradation, Kafka analytics monitoring, and pseudocode with primaryDb.findByAlias.
                        """, 0.80)
        );

        for (ProductionCase scenario : productionCases) {
            var quality = ProductionConsistencyCalibrator.qualityScore(scenario.answer(), null, 0.95);
            assertTrue(quality.score() <= scenario.maxScore(),
                    scenario.name() + " scored " + quality.score() + " but expected <= " + scenario.maxScore());
        }

        ResearchPack pack = ResearchPack.withSources(
                "Prompt asks for current information.",
                List.of("latest model routing"),
                List.of(new ResearchSource("S1", "Routing source", "https://example.com/routing",
                        "example.com", "Routing chooses models dynamically.", "2026-01-01", 0.9)));
        assertTrue(ResearchQualityCalibrator.qualityScore(
                "The latest routing architecture is definitely settled, but this answer cites nothing.",
                pack,
                0.95).score() <= 0.72);
        assertTrue(ResearchQualityCalibrator.qualityScore(
                "The latest routing architecture is settled [S2].",
                pack,
                0.95).score() <= 0.62);
    }

    private ProductionCase payment(String name, String answer, double maxScore) {
        return new ProductionCase(name, answer + " wallet payment transfer idempotency PostgreSQL", maxScore);
    }

    private ProductionCase redirect(String name, String answer, double maxScore) {
        return new ProductionCase(name, answer + " Redis PostgreSQL Kafka deleted redirect URL", maxScore);
    }

    private record ProductionCase(String name, String answer, double maxScore) {}
}
