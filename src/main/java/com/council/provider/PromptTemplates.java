package com.council.provider;

import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.VerifierBatchResult;
import com.council.model.VerifierVerdict;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt templates for draft generation and critic evaluation.
 * All templates produce instructions that demand strict JSON output.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>No fence-sitting:</b> Drafters must make definitive choices and defend them.</li>
 *   <li><b>Show the math:</b> Scale-related prompts require concrete numerical estimations.</li>
 *   <li><b>Concrete failures:</b> Failure scenarios must include exact mitigation steps.</li>
 *   <li><b>Critic aggression:</b> Nemotron penalises vagueness, missing math, and boilerplate.</li>
 * </ul>
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    // ── Draft prompt ──────────────────────────────────────────────────────────

    public static String buildDraftPrompt(String userQuery) {
        return """
                You are an elite senior engineer and systems architect, one of several models \
                competing to produce the BEST answer in a multi-model evaluation. \
                Only the most specific, defensible, and mathematically grounded answer wins.

                ═══ MANDATORY RULES — FAILURE TO FOLLOW = DISQUALIFICATION ═══

                1. OUTPUT FORMAT
                   - Output STRICT JSON only. No markdown fences, no prose outside JSON.
                   - Adhere exactly to the schema below.
                   - confidence MUST be a numeric value between 0.0 and 1.0.

                2. NO FENCE-SITTING
                   - You MUST make definitive architectural choices and defend them.
                   - Do NOT offer "either/or" or "it depends" without immediately resolving \
                the decision with a concrete recommendation backed by reasoning.
                   - Saying "you could use X or Y" without picking one is wrong. Pick one.
                   - Forbidden: "monolith OR microservices". Required: pick ONE and defend it.

                3. SHOW THE MATH
                   - If the prompt involves scale (users, TPS, QPS, GB/day, partitions, \
                nodes), you MUST provide concrete numerical estimations.
                   - Include at least one explicit equation when scale is discussed.
                   - Examples: "10 000 users × 5 writes/min = 833 writes/sec → 3 Kafka \
                partitions at 300 msg/s each", "p99 latency budget: 500ms total — \
                100ms DB read + 50ms cache miss + 350ms headroom for retries".
                   - Round numbers are a red flag. Use real engineering math.

                4. CONCRETE FAILURE SCENARIOS
                   - Do not just list failure types. Simulate a SPECIFIC failure scenario \
                and give the EXACT mitigation: e.g., "If the payment provider returns \
                HTTP 503: retry twice with 150 ms exponential backoff, check idempotency \
                key on retry, trip circuit breaker after 5 consecutive failures within 30s, \
                fall back to provider B".
                   - Every significant component must have ≥1 concrete failure + mitigation.

                5. OPERATIONAL REALISM
                   - Your answer must be actionable for a principal engineer ON CALL at 3 AM.
                   - Avoid Wikipedia-level descriptions. Prefer runbook-level specificity.

                6. PRINCIPAL ENGINEER CONSTRAINTS
                   - FINANCIAL INTEGRITY: If designing transactional/payment systems, you MUST use
                     strongly consistent datastores (e.g., PostgreSQL/RDBMS) for the core ledger.
                     You may use NoSQL only for logs/analytics.
                            - PRODUCTION MATH: Storage math must include retention policies (TTL), WAL
                               (write-ahead log) overhead, index-size multipliers, and replication factors.
                               Show the final capacity equation explicitly.
                            - KAFKA REALISM: Kafka throughput math must include consumer batch size,
                               lag tolerance (max acceptable backlog), and processing latency per batch.
                               Do NOT use generic guesses like "300 msg/s" without deriving them from
                               processing constraints.
                   - RESILIENCY: All retry mechanisms MUST include exponential backoff WITH jitter.
                     Circuit breakers must use sliding window error-rate thresholds, not fixed counts.

                ═══ JSON SCHEMA ═══
                {
                  "answer": "string  — full technical answer (be thorough)",
                  "summary": "string — one-line executive summary of your decision",
                  "assumptions": ["string — explicit assumption you are making"],
                  "uncertainties": ["string — what you genuinely do not know"],
                  "confidence": 0.0
                }

                User Query:
                """ + userQuery + productionConsistencyRubric(userQuery);
    }

      // ── Verifier prompt ──────────────────────────────────────────────────────

      public static String buildVerifierPrompt(String userQuery, List<DraftResult> drafts) {
            String draftSection = (drafts == null ? List.<DraftResult>of() : drafts).stream()
                    .map(PromptTemplates::formatDraftForVerifier)
                    .collect(Collectors.joining(",\n"));

            return """
                              You are a FINAL CONSTRAINT ENFORCER.

                              Your job is to REJECT physically impossible system designs.

                              You MUST NOT score.
                              You MUST NOT explain.
                              You MUST ONLY decide VALID or REJECT.

                              INPUT:
                              You receive computed values, verifier results, and system parameters.

                              Apply all rules per draft id:

                              RULE 1: THROUGHPUT LIMIT
                              - Assume max_per_partition = 1000 msg/sec.
                              - required_partitions = ceil(inputTPS / max_per_partition).
                              - If actual_partitions < required_partitions => REJECT.

                              RULE 2: DLQ CAPACITY
                              - dlq_load_per_partition = dlq_tps / dlq_partitions.
                              - If dlq_load_per_partition > 1000 => REJECT.

                              RULE 3: LATENCY REALITY
                              - If per_message_latency < 0.5 ms => REJECT.

                              RULE 4: INTERNAL CONSISTENCY
                              - If any derived number contradicts another => REJECT.

                              RULE 5: FAIL FAST
                              - If ANY rule fails, return:
                                 { "valid": false, "reason": "constraint violation" }
                              - Otherwise, return:
                                 { "valid": true }

                              IMPORTANT:
                              - NO partial credit.
                              - NO scoring.
                              - NO reasoning.
                              - ONLY PASS or REJECT.

                              OUTPUT FORMAT (STRICT JSON BATCH):
                              {
                                 "verdicts": {
                                    "<draftId>": {
                                       "valid": true
                                    }
                                 }
                              }

                              For rejected drafts:
                              {
                                 "verdicts": {
                                    "<draftId>": {
                                       "valid": false,
                                       "reason": "constraint violation"
                                    }
                                 }
                              }

                    INPUT PAYLOAD:
                    {
                      "userQuery": "%s",
                      "drafts": [
                        %s
                      ]
                    }

                    Use each draft "id" exactly as key under "verdicts".
                    Output pure JSON only.
                    """.formatted(escapeForPromptJson(userQuery), draftSection);
      }

      /**
       * Backward-compatible overload used by legacy tests/callers.
       */
      public static String buildVerifierPrompt(String userQuery, DraftResult draft) {
            return buildVerifierPrompt(userQuery, List.of(draft));
      }

      // ── Synthesizer prompt ───────────────────────────────────────────────────

      public static String buildSynthesizerPrompt(String userQuery,
                                                                        List<DraftResult> drafts,
                                                                        VerifierBatchResult verifierBatchResult,
                                                                        CriticResult criticResult) {
            List<DraftResult> safeDrafts = drafts == null ? List.of() : drafts;

            String draftSection = safeDrafts.stream()
                        .map(PromptTemplates::formatDraftForSynthesis)
                        .collect(Collectors.joining("\n\n---\n\n"));

            String verifierSection = formatVerifierForSynthesis(safeDrafts, verifierBatchResult);
            String criticSection = formatCriticForSynthesis(criticResult);

            return """
                        You are the SYNTHESIZER in a multi-model reasoning system.

                        Your job is NOT to select the best draft.
                        Your job is to construct a new final answer that is better than every individual draft.

                        You will receive:
                        1) The original user query
                        2) Multiple candidate drafts
                        3) Verifier results for each draft
                        4) Critic notes, if any

                        Your output must be a single unified answer that:
                        - preserves the strongest validated ideas from the drafts
                        - removes weak, redundant, contradictory, or unsafe claims
                        - resolves disagreements with explicit final decisions
                        - improves clarity, completeness, and practical usefulness
                        - obeys all verifier constraints
                        - does NOT merely copy one draft
                        - does NOT mention internal model names or the existence of drafts

                        SYNTHESIS RULES

                        1. Merge, do not vote.
                            - Combine the best parts from different drafts into one coherent answer.
                            - If Draft A has better math and Draft B has better failure handling, keep both strengths.
                            - Do not return a plain winner selection.

                        2. Hard reject invalid content.
                            - If the verifier marks a draft with fatal math, consistency violation, or throughput contradiction, do not use that claim.
                            - Only reuse claims that survive verification.

                        3. Resolve conflicts decisively.
                            - If drafts disagree on architecture, storage, throughput, retries, or failure handling, choose one final path and explain why it is the best choice.
                            - Do not hedge with "it depends" unless the user explicitly asked for alternatives.

                        4. Prefer the strongest validated reasoning.
                            - Keep exact numbers when they are correct.
                            - Recompute any critical equation yourself if the drafts conflict.
                            - Fix unit conversions, throughput estimates, storage math, and retry logic.

                        5. Improve the answer.
                            - Fill missing gaps.
                            - Tighten weak explanations.
                            - Make the final answer more operational, more precise, and more realistic than any input draft.
                            - Do not summarize away required sections from the original prompt.
                            - Never write "provided below", "as follows", or similar unless the promised content immediately follows in the synthesizedAnswer.

                        6. Be structurally clean.
                            - Use a clear architecture.
                            - Put decisions first.
                            - Then math.
                            - Then failure handling.
                            - Then trade-offs or assumptions.
                            - End with a crisp summary.
                            - If the original prompt asks for pseudocode or an algorithm, include actual code-like control flow with if/else/return statements.

                        7. Stay grounded.
                            - Do not invent unsupported facts.
                            - Do not inflate confidence.
                            - Do not add unnecessary complexity.

                        OUTPUT FORMAT

                        Return valid JSON only, with this schema:

                        {
                           "synthesizedAnswer": "string",
                           "summary": "string",
                           "decisions": [
                              "string"
                           ],
                           "mergedStrengths": [
                              "string"
                           ],
                           "discardedClaims": [
                              "string"
                           ],
                           "assumptions": [
                              "string"
                           ],
                           "uncertainties": [
                              "string"
                           ],
                           "confidence": 0.0
                        }

                        QUALITY BAR

                        The final answer should read like a principal engineer wrote it after reviewing multiple drafts, not like a summary of them.

                        %s

                        Original User Query:
                        %s

                        Candidate Drafts:
                        %s

                        Verifier Results:
                        %s

                        Critic Notes:
                        %s
                        """.formatted(productionConsistencyRubric(userQuery), userQuery, draftSection, verifierSection, criticSection);
      }

    // ── Critic prompt ─────────────────────────────────────────────────────────

    public static String buildCriticPrompt(String userQuery, List<DraftResult> drafts) {
        String draftSummaries = drafts.stream()
                .map(PromptTemplates::formatDraftForCritic)
                .collect(Collectors.joining("\n\n---\n\n"));

        return """
         CRITICAL: YOU MUST OUTPUT ONLY VALID JSON. DO NOT INCLUDE PREAMBLES, GREETINGS, OR MARKDOWN. YOUR OUTPUT MUST START WITH '{' AND END WITH '}'.

                You are NEMOTRON, the adversarial critic in a multi-model reasoning system. \
                Your job is to DESTROY weak answers. You are merciless, precise, and allergic to bullshit.

                You will receive multiple candidate answers to the same user query. \
                Your critique directly determines which answer wins. Be brutally honest.

                ═══ WHAT TO PENALISE AGGRESSIVELY ═══

                A. VAGUE TRADE-OFFS / LACK OF CONVICTION
                   - Answers that say "X or Y depending on your needs" without resolving the \
                choice are WEAK. Dock them for intellectual cowardice.
                   - Flag any answer that presents options instead of decisions.

                B. MISSING MATHEMATICAL JUSTIFICATION FOR SCALE
                   - If the question involves load, throughput, storage, or latency, any \
                answer that does NOT provide concrete numbers (QPS, p99, partition count, \
                GB/day, replication factor) is INCOMPLETE.
                   - Set genericnessPenalty HIGH (0.7–1.0) for math-free architecture answers.

                C. TEXTBOOK / BOILERPLATE DESIGNS
                   - Answers that describe well-known patterns (CQRS, saga, event sourcing) \
                without explaining WHY they apply to THIS specific problem are boilerplate.
                   - Ask: "Could this answer have been generated by reading the Wikipedia \
                page?" If yes, penalise it.

                D. MISSING FAILURE MODES
                   - Production systems fail. Any answer that does not simulate at least ONE \
                concrete failure path with exact mitigation steps is operationally useless.

                E. PRODUCTION-SAFETY RED FLAGS (PENALISE HARD)
                   - If a draft uses eventually consistent databases (Cassandra/NoSQL) as the
                     core transactional/payment ledger, aggressively penalize feasibilityScore
                     (typically <= 0.2 unless the draft explicitly corrects this by moving core
                     ledger writes to a strongly consistent RDBMS).
                   - If storage math ignores TTL/retention windows and compaction overhead,
                     aggressively penalize mathCorrectnessScore.
                   - If retries do NOT include jitter, max retry windows, and asynchronous DLQ
                     fallback paths, aggressively penalize failureDepthScore.
                   - If circuit breaker limits are arbitrary fixed counts (e.g., "trip after 5
                     failures") without sliding-window percentage-based error thresholds,
                     penalize feasibilityScore.
                   - If Kafka partition math ignores consumer throughput and batching effects,
                     penalize mathCorrectnessScore.

                ═══ INTER-DRAFT DISAGREEMENT ═══

                If the drafts DISAGREE on a key architectural choice (e.g., one picks \
                microservices, another monolith; one picks Kafka, another RabbitMQ), you MUST \
                surface this in contradictionsFound and explain which position is better \
                defended and why. Do not gloss over disagreements.
                If disagreement handling is weak, unresolved, or "it depends" driven, set \
                weakTradeoffAnalysis=true and call this out explicitly in globalSummary.

                ═══ SCORING GUIDANCE ═══

                        - genericnessPenalty: 0.0 = highly specific / concrete, 1.0 = pure boilerplate
                        - contradictionSeverity: 0.0 = all drafts agree, 1.0 = fundamental disagreements
                        - mathCorrectnessScore: 0.0 = broken math, 1.0 = correct and complete math
                        - feasibilityScore: 0.0 = unsafe/unbuildable, 1.0 = production-safe realism
                        - failureDepthScore: 0.0 = shallow failure handling, 1.0 = deep mitigations
                        - weakTradeoffAnalysis: true if ANY answer avoids making a decision OR key
                           inter-draft disagreements are weakly defended / unresolved
                        - Set missingMathJustification: true if scale-related question has no numbers
                        - winnerRationale: state EXACTLY why the best draft beat the others — \
                           reference specific content (e.g., "nvidia provided Kafka partition math: \
                           10k msg/s ÷ 300/partition = 34 partitions; others gave no numbers")

                        CRITICAL TYPE ENFORCEMENT: All score fields (mathCorrectnessScore, feasibilityScore, failureDepthScore, genericnessPenalty, contradictionSeverity) MUST BE RAW NUMBERS (e.g., 0.9) AND NEVER WRAPPED IN QUOTES (e.g., '0.9'). YOU MUST INCLUDE EVERY FIELD LISTED IN THE SCHEMA.

                        %s

                        ═══ JSON SCHEMA ═══
                {
                  "globalSummary": "string — one paragraph critique of all drafts",
                  "contradictionSeverity": 0.0,
                  "contradictionCountPerDraft": { "providerName": 0 },
                  "contradictionsFound": [
                    { "draftA": "string", "draftB": "string", "issue": "string", "verdict": "string" }
                  ],
                  "missingPoints": ["string"],
                  "riskyClaims": ["string"],
                  "mathCorrectnessScore": 0.0,
                  "feasibilityScore": 0.0,
                  "failureDepthScore": 0.0,
                  "genericnessPenalty": 0.0,
                  "missingFailureModes": ["string"],
                  "weakTradeoffAnalysis": false,
                  "missingMathJustification": false,
                  "winnerRationale": "string — name the best draft and quote the specific content that made it win"
                }

                User Query:
                """.formatted(productionConsistencyRubric(userQuery)) + userQuery + "\n\nCandidate Drafts:\n\n" + draftSummaries;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String productionConsistencyRubric(String userQuery) {
        if (!isCacheDeletionConsistencyPrompt(userQuery)) {
            return "";
        }

        return """

                SCENARIO-SPECIFIC CONSISTENCY RUBRIC

                This prompt is a stale-read/cache-deletion incident. Penalize shallow answers hard.

                Required production conclusions:
                - Redirect correctness is authoritative over analytics freshness. Kafka/consumer lag may make dashboards stale, but it must not change the redirect decision.
                - A URL deleted 1 second ago must not redirect. Return 404 Not Found, or 410 Gone if the product intentionally reveals prior existence.
                - Redis TTL/expiration alone is not sufficient deletion consistency. A cached active redirect with a 5 minute TTL can still create stale redirects.
                - On deletion, write deleted_at/version/tombstone to the primary database, invalidate or overwrite Redis immediately, and store a short-lived DELETED/negative-cache tombstone.
                - During the replica-lag window, the redirect path must not trust a PostgreSQL replica that can be 2 seconds stale. It must use the Redis tombstone, a primary read, or a deletion/version check that is safe under lag.
                - Cache stampede handling must include singleflight/request coalescing/per-key lock/distributed lock. Retry/backoff alone is not enough because it can still fan out many identical DB reads.
                - Do not return a cached "lease" or "maybe stale" response to the browser. A redirect endpoint must make a binary correctness decision: redirect only if active is proven, otherwise return 404/410.
                - Pseudocode must be concrete control flow, not a prose checklist.
                - Metrics/logs/alerts must distinguish redirect correctness from analytics lag and include Redis p95/p99 latency/timeouts, DB replica lag, primary fallback rate, tombstone hits, stale-cache prevention, lock wait/load count, Kafka consumer lag, and dashboard freshness.

                Scoring caps for the critic:
                - If a draft treats Redis TTL/expiration as sufficient stale-delete protection, cap feasibilityScore at 0.60.
                - If a draft trusts lagging replicas despite 2 second replica lag and a 1 second old deletion, cap feasibilityScore at 0.55.
                - If a draft allows a maybe-stale lease response on the synchronous redirect path, cap feasibilityScore at 0.55.
                - If a draft misses two of tombstone/negative cache, primary-read or safe version check, singleflight/coalescing, analytics-vs-redirect separation, cap feasibilityScore at 0.70.
                - If a draft misses three or more of those controls, cap feasibilityScore at 0.55 and failureDepthScore at 0.65.
                - If pseudocode is only a plain-English list, cap failureDepthScore at 0.65.
                """;
    }

    private static boolean isCacheDeletionConsistencyPrompt(String userQuery) {
        if (userQuery == null) {
            return false;
        }
        String q = userQuery.toLowerCase();
        return q.contains("redis")
                && (q.contains("postgres") || q.contains("replica"))
                && (q.contains("deleted") || q.contains("deletion"))
                && (q.contains("kafka") || q.contains("analytics"))
                && (q.contains("url-shortener") || q.contains("short url") || q.contains("redirect"));
    }

    private static String formatDraftForCritic(DraftResult draft) {
        return ("Draft from %s (model=%s, confidence=%.2f):" +
                "\nAnswer: %s" +
                "\nSummary: %s" +
                "\nAssumptions: %s" +
                "\nUncertainties: %s")
                .formatted(
                        draft.provider(),
                        draft.model(),
                        draft.confidence(),
                        draft.answer(),
                        draft.summary(),
                        String.join(", ", draft.assumptions()),
                        String.join(", ", draft.uncertainties())
                );
    }

      private static String formatDraftForVerifier(DraftResult draft) {
         return ("{\n" +
            "  \"id\": \"%s\",\n" +
            "  \"content\": \"%s\"\n" +
            "}")
               .formatted(
                     draft.provider(),
               escapeForPromptJson(draft.answer())
               );
      }

          private static String escapeForPromptJson(String value) {
         if (value == null) {
             return "";
         }
         return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ");
          }

      private static String formatDraftForSynthesis(DraftResult draft) {
         return ("Provider: %s" +
               "\nModel: %s" +
               "\nAnswer: %s" +
               "\nSummary: %s" +
               "\nAssumptions: %s" +
               "\nUncertainties: %s" +
               "\nConfidence: %.2f")
               .formatted(
                     draft.provider(),
                     draft.model(),
                     draft.answer(),
                     draft.summary(),
                     String.join(", ", draft.assumptions()),
                     String.join(", ", draft.uncertainties()),
                     draft.confidence()
               );
      }

      private static String formatVerifierForSynthesis(List<DraftResult> drafts,
                                           VerifierBatchResult verifierBatchResult) {
         if (verifierBatchResult == null) {
            return "No verifier results available.";
         }
         if (verifierBatchResult.isInternalError()) {
            return "Verifier internal error: " + verifierBatchResult.internalErrorReason();
         }

         StringBuilder sb = new StringBuilder();
         for (DraftResult draft : drafts) {
            VerifierVerdict verdict = verifierBatchResult.verdictForProvider(draft.provider());
            sb.append("Provider: ").append(draft.provider())
                  .append(" | containsFatalMathError=").append(verdict.containsFatalMathError())
                  .append(", containsConsistencyViolation=").append(verdict.containsConsistencyViolation())
                  .append(", containsThroughputContradiction=").append(verdict.containsThroughputContradiction())
                  .append(", fatalErrorReason=").append(verdict.fatalErrorReason())
                  .append("\n");
         }
         return sb.toString().trim();
      }

      private static String formatCriticForSynthesis(CriticResult criticResult) {
         if (criticResult == null || !criticResult.isSuccess()) {
            return "No critic notes available.";
         }

         return ("globalSummary=%s" +
               "\ncontradictionSeverity=%.2f" +
               "\nmissingPoints=%s" +
               "\nriskyClaims=%s" +
               "\nmathCorrectnessScore=%.2f" +
               "\nfeasibilityScore=%.2f" +
               "\nfailureDepthScore=%.2f")
               .formatted(
                     criticResult.globalSummary(),
                     criticResult.contradictionSeverity(),
                     criticResult.missingPoints(),
                     criticResult.riskyClaims(),
                     criticResult.mathCorrectnessScore(),
                     criticResult.feasibilityScore(),
                     criticResult.failureDepthScore()
               );
      }
}
