package com.ciaozn.alphatrader.backtest;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.risk.RiskPipeline;

/**
 * Builds one run's rule set against that run's book.
 *
 * <p>Deferred rather than a plain {@link RiskPipeline} because one rule needs the book: the circuit
 * breaker counts consecutive losing trades by observing {@code FillEvent}s and reading the change in
 * the {@link Portfolio}'s realized P&L. The book does not exist until the run starts - the runner
 * creates it from {@code startingEquity} so that two runs off one wiring cannot share an account - so
 * the rule set cannot be finished before then either.
 *
 * <p>The alternative, handing the caller a {@code Portfolio} to build the pipeline against and passing
 * both in, moves the book's lifecycle out of the runner and out of step with the strategies': a second
 * {@code run()} on one config would replay into warm strategies <em>and</em> a dirty book, and the
 * two would disagree about how much of the run had already happened.
 */
@FunctionalInterface
public interface RiskPipelineFactory {

    RiskPipeline create(Portfolio portfolio);

    /** A rule set that needs no book - including the empty one, meaning "no rules beyond sizing". */
    static RiskPipelineFactory fixed(RiskPipeline pipeline) {
        return portfolio -> pipeline;
    }
}
