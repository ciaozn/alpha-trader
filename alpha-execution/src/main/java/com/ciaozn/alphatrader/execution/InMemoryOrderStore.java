package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.FillEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link OrderStore} that does no I/O: what the OMS is tested against, and what any mode that
 * runs without a business database is given.
 *
 * <p>This is a store and not a cache - it keeps everything and forgets nothing, so a long run grows
 * without bound. That is the honest price of not touching a disk, and it is why the JDBC
 * implementation exists rather than this being the shipped default.
 *
 * <p>Insertion-ordered throughout, so a listing depends on the order orders arrived and not on the
 * hash of anything (NFR-04): {@code save} on a known {@code clientOrderId} replaces the row in place,
 * which is the {@code LinkedHashMap} contract and exactly the invariant {@code findOpen()} needs.
 * Not synchronized, per the contract's threading rule - every call arrives on the engine thread.
 */
public final class InMemoryOrderStore implements OrderStore {

    private final Map<String, OrderRecord> orders = new LinkedHashMap<>();
    private final Map<String, List<FillEvent>> fills = new LinkedHashMap<>();

    @Override
    public void save(OrderRecord order) {
        orders.put(order.clientOrderId(), order);
    }

    @Override
    public void saveFill(FillEvent fill) {
        fills.computeIfAbsent(fill.clientOrderId(), id -> new ArrayList<>()).add(fill);
    }

    @Override
    public Optional<OrderRecord> find(String clientOrderId) {
        return Optional.ofNullable(orders.get(clientOrderId));
    }

    @Override
    public List<OrderRecord> findOpen() {
        return orders.values().stream().filter(OrderRecord::isOpen).toList();
    }

    @Override
    public List<FillEvent> fills(String clientOrderId) {
        return List.copyOf(fills.getOrDefault(clientOrderId, List.of()));
    }
}
