package com.council.judge.invariant;

/**
 * Machine-readable invariant contract used by the dynamic evaluator.
 *
 * @param domain owning evaluation domain
 * @param id stable invariant identifier
 * @param title short display title
 * @param description exact correctness rule being checked
 * @param severity default severity when violated
 * @param scoreCap maximum answer quality allowed by this violation
 */
public record InvariantDefinition(
        InvariantDomain domain,
        String id,
        String title,
        String description,
        InvariantSeverity severity,
        double scoreCap
) {}
