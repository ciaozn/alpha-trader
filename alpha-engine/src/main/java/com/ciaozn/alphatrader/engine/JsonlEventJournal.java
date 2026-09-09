package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderUpdateEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.event.TickerEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON Lines event journal: one event per line, flushed on every append.
 * Volume is small for k-line trading (< 100 events/s), durability beats throughput here.
 */
public final class JsonlEventJournal implements EventJournal {

    private static final Logger log = LoggerFactory.getLogger(JsonlEventJournal.class);

    private final Path path;
    private final BufferedWriter writer;
    private final ObjectMapper mapper;

    public JsonlEventJournal(Path path) {
        this.path = path;
        this.mapper = eventMapper();
        try {
            Files.createDirectories(path.getParent());
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open event journal: " + path, e);
        }
    }

    /** ObjectMapper with the sealed event hierarchy explicitly registered (deterministic). */
    public static ObjectMapper eventMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerSubtypes(
                new NamedType(KlineEvent.class, "kline"),
                new NamedType(TickerEvent.class, "ticker"),
                new NamedType(SignalEvent.class, "signal"),
                new NamedType(OrderRequestEvent.class, "orderRequest"),
                new NamedType(OrderReportEvent.class, "orderReport"),
                new NamedType(OrderUpdateEvent.class, "orderUpdate"),
                new NamedType(FillEvent.class, "fill"),
                new NamedType(TimerEvent.class, "timer"),
                new NamedType(RiskAlertEvent.class, "riskAlert"));
        return mapper;
    }

    @Override
    public synchronized void append(Event event) {
        try {
            writer.write(mapper.writeValueAsString(event));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // Losing the journal means losing recovery/audit - scream loudly.
            log.error("Event journal append failed at {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Event> readAll() {
        List<Event> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(mapper.readValue(line, Event.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read event journal: " + path, e);
        }
        return events;
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Event journal close failed", e);
        }
    }
}
