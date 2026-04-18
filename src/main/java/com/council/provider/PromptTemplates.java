package com.council.provider;

import com.council.model.DraftResult;

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
                """ + userQuery;
    }

      // ── Verifier prompt ──────────────────────────────────────────────────────

      public static String buildVerifierPrompt(String userQuery, DraftResult draft) {
            return """
                        CRITICAL: YOU MUST OUTPUT ONLY VALID JSON. DO NOT INCLUDE PREAMBLES, GREETINGS, OR MARKDOWN. YOUR OUTPUT MUST START WITH '{' AND END WITH '}'.

                        You are a mathematical calculator and constraint verifier. You do NOT evaluate architecture quality. Your ONLY job is to extract every equation, unit conversion (KB/MB/GB/TB/PB), and magnitude estimation in the draft and RECALCULATE IT YOURSELF.

                        If 4,000,000,000 KB is claimed to be 4 GB, you must flag containsFatalMathError = true.
                        If a CORE FINANCIAL LEDGER uses Cassandra/MongoDB, flag containsConsistencyViolation = true. However, you MUST ALLOW the use of NoSQL (Cassandra/MongoDB) for logs, analytics, and telemetry. Do NOT flag a violation if NoSQL is strictly isolated to non-ledger data.

                        Rules:
                        1. Recompute every numeric claim from scratch.
                        2. Validate unit conversions explicitly (KB->MB->GB->TB->PB).
                        3. Validate capacity and throughput equations end-to-end.
                        4. If any fatal math or unit error exists, set containsFatalMathError=true.
                        5. If any core-ledger consistency violation exists, set containsConsistencyViolation=true.
                        5a. ALLOW NoSQL for logs/analytics/telemetry. Only flag consistency violations when NoSQL is used for the core financial ledger itself.
                        6. If either boolean is true, fatalErrorReason MUST explain the exact failing claim.
                        7. If both booleans are false, fatalErrorReason MUST be null.

                        ═══ JSON SCHEMA ═══
                        {
                           "containsFatalMathError": false,
                           "containsConsistencyViolation": false,
                           "fatalErrorReason": null
                        }

                        User Query:
                        %s

                        Draft Under Verification:
                        Provider: %s
                        Model: %s
                        Answer:
                        %s
                        """.formatted(userQuery, draft.provider(), draft.model(), draft.answer());
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
                """ + userQuery + "\n\nCandidate Drafts:\n\n" + draftSummaries;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
}
