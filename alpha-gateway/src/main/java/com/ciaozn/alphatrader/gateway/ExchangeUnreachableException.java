package com.ciaozn.alphatrader.gateway;

/**
 * The exchange did not answer (边界 6): the connection failed, the request timed out, or the exchange
 * is in a maintenance window or replying 5xx.
 *
 * <p><b>This is deliberately not an {@link OrderAck} rejection.</b> A rejection is an answer - the
 * exchange took the request and refused it, so the order is definitively not on the book. No answer
 * leaves the outcome unknown: the request may have been accepted a moment before the connection died.
 * A caller that records "unknown" as "rejected" tells the strategy its order was refused, the strategy
 * re-signals, and the position that was resting on the exchange now has a second one beside it.
 *
 * <p><b>Unchecked, because the callers that matter most cannot declare one.</b> {@code EventHandler}
 * has no {@code throws} clause, and reconciliation reaches the exchange from a timer on the engine
 * thread; a checked exception here would be wrapped at every such call site, and the wrapper is the
 * type that gets caught - so the distinction would survive the compiler and not the code.
 *
 * <p>What a caller must do with it is uniform, which is why losing the distinction is survivable and
 * losing the response is not: do not advance the order state machine, and let reconciliation ask the
 * exchange what it actually holds (FR-EX-04). A caller that catches only {@code RuntimeException}
 * therefore still does the safe thing - it just cannot tell the operator whether the exchange was down
 * or the send path was broken.
 */
public class ExchangeUnreachableException extends RuntimeException {

    public ExchangeUnreachableException(String message) {
        super(message);
    }

    public ExchangeUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
