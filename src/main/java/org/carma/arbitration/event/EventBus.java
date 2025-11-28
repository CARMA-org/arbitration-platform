package org.carma.arbitration.event;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Event bus for publish-subscribe communication.
 * 
 * Provides:
 * - Type-safe subscription
 * - Synchronous event dispatch
 * - Event history for audit trail
 */
public class EventBus {
    
    private final Map<Class<? extends Event>, List<Consumer<Event>>> subscribers;
    private final List<Event> eventHistory;
    private final boolean recordHistory;

    public EventBus() {
        this(true);
    }

    public EventBus(boolean recordHistory) {
        this.subscribers = new ConcurrentHashMap<>();
        this.eventHistory = Collections.synchronizedList(new ArrayList<>());
        this.recordHistory = recordHistory;
    }

    // ========================================================================
    // Subscription
    // ========================================================================

    /**
     * Subscribe to a specific event type.
     */
    @SuppressWarnings("unchecked")
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
            .add(event -> handler.accept((T) event));
    }

    /**
     * Subscribe to all events.
     */
    public void subscribeAll(Consumer<Event> handler) {
        // Subscribe to all known event types
        subscribe(Event.ResourceRequestEvent.class, handler::accept);
        subscribe(Event.ContentionDetectedEvent.class, handler::accept);
        subscribe(Event.ArbitrationCompleteEvent.class, handler::accept);
        subscribe(Event.AllocationEnforcedEvent.class, handler::accept);
        subscribe(Event.ResourceReleaseEvent.class, handler::accept);
        subscribe(Event.CurrencyMintedEvent.class, handler::accept);
        subscribe(Event.CurrencyBurnedEvent.class, handler::accept);
        subscribe(Event.SimulationTickEvent.class, handler::accept);
    }

    /**
     * Unsubscribe a handler from an event type.
     */
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<Event>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            handlers.removeIf(h -> h.equals(handler));
        }
    }

    // ========================================================================
    // Publishing
    // ========================================================================

    /**
     * Publish an event to all subscribers.
     */
    public void publish(Event event) {
        if (recordHistory) {
            eventHistory.add(event);
        }
        
        List<Consumer<Event>> handlers = subscribers.get(event.getClass());
        if (handlers != null) {
            for (Consumer<Event> handler : handlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    System.err.println("Error in event handler: " + e.getMessage());
                }
            }
        }
    }

    // ========================================================================
    // History Management
    // ========================================================================

    /**
     * Get all recorded events.
     */
    public List<Event> getHistory() {
        return new ArrayList<>(eventHistory);
    }

    /**
     * Get events of a specific type.
     */
    @SuppressWarnings("unchecked")
    public <T extends Event> List<T> getHistory(Class<T> eventType) {
        List<T> filtered = new ArrayList<>();
        for (Event event : eventHistory) {
            if (eventType.isInstance(event)) {
                filtered.add((T) event);
            }
        }
        return filtered;
    }

    /**
     * Get event count.
     */
    public int getEventCount() {
        return eventHistory.size();
    }

    /**
     * Get event count by type.
     */
    public int getEventCount(Class<? extends Event> eventType) {
        return (int) eventHistory.stream()
            .filter(eventType::isInstance)
            .count();
    }

    /**
     * Clear event history.
     */
    public void clearHistory() {
        eventHistory.clear();
    }

    /**
     * Clear all subscribers.
     */
    public void clearSubscribers() {
        subscribers.clear();
    }

    /**
     * Reset the event bus.
     */
    public void reset() {
        clearHistory();
        clearSubscribers();
    }

    @Override
    public String toString() {
        return String.format("EventBus[subscribers=%d, history=%d events]",
            subscribers.values().stream().mapToInt(List::size).sum(),
            eventHistory.size());
    }
}
