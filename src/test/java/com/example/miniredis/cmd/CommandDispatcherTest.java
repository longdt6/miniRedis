package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandDispatcherTest {

    private Store store;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        store = new Store();
        dispatcher = new CommandDispatcher();
        dispatcher.init();
    }

    @Test
    void ping_noArgs() {
        Reply r = dispatcher.execute(store, "PING", List.of());
        assertInstanceOf(Reply.Simple.class, r);
        assertEquals("PONG", ((Reply.Simple) r).value());
    }

    @Test
    void ping_echo() {
        Reply r = dispatcher.execute(store, "PING", List.of("hello"));
        assertInstanceOf(Reply.Simple.class, r);
        assertEquals("hello", ((Reply.Simple) r).value());
    }

    @Test
    void setGetRoundTrip() {
        dispatcher.execute(store, "SET", List.of("foo", "bar"));
        Reply r = dispatcher.execute(store, "GET", List.of("foo"));
        assertInstanceOf(Reply.Bulk.class, r);
        assertEquals("bar", ((Reply.Bulk) r).value());
        assertFalse(((Reply.Bulk) r).isNil());
    }

    @Test
    void getMissingReturnsNilBulk() {
        Reply r = dispatcher.execute(store, "GET", List.of("missing"));
        assertInstanceOf(Reply.Bulk.class, r);
        assertTrue(((Reply.Bulk) r).isNil());
    }

    @Test
    void delReturnsCount() {
        dispatcher.execute(store, "SET", List.of("a", "1"));
        dispatcher.execute(store, "SET", List.of("b", "2"));
        Reply r = dispatcher.execute(store, "DEL", List.of("a", "b", "nope"));
        assertInstanceOf(Reply.Int.class, r);
        assertEquals(2L, ((Reply.Int) r).value());
    }

    @Test
    void incr_notInteger() {
        dispatcher.execute(store, "SET", List.of("c", "abc"));
        Reply r = dispatcher.execute(store, "INCR", List.of("c"));
        assertInstanceOf(Reply.Error.class, r);
        assertTrue(((Reply.Error) r).message().toLowerCase().contains("integer"));
    }

    @Test
    void incrWorks() {
        Reply r = dispatcher.execute(store, "INCR", List.of("counter"));
        assertInstanceOf(Reply.Int.class, r);
        assertEquals(1L, ((Reply.Int) r).value());
    }

    @Test
    void keysListsAll() {
        dispatcher.execute(store, "SET", List.of("a", "1"));
        dispatcher.execute(store, "SET", List.of("b", "2"));
        Reply r = dispatcher.execute(store, "KEYS", List.of());
        assertInstanceOf(Reply.Array.class, r);
        assertEquals(2, ((Reply.Array) r).values().size());
    }

    @Test
    void unknownCommandReturnsError() {
        Reply r = dispatcher.execute(store, "NOSUCH", List.of());
        assertInstanceOf(Reply.Error.class, r);
        assertTrue(((Reply.Error) r).message().contains("unknown"));
    }

    @Test
    void expireSetsTtl() {
        dispatcher.execute(store, "SET", List.of("k", "v"));
        Reply r = dispatcher.execute(store, "EXPIRE", List.of("k", "30"));
        assertInstanceOf(Reply.Int.class, r);
        assertEquals(1L, ((Reply.Int) r).value());
        Reply t = dispatcher.execute(store, "TTL", List.of("k"));
        assertInstanceOf(Reply.Int.class, t);
        assertTrue(((Reply.Int) t).value() > 0);
    }

    @Test
    void ttlMissingReturnsMinusTwo() {
        Reply r = dispatcher.execute(store, "TTL", List.of("nope"));
        assertInstanceOf(Reply.Int.class, r);
        assertEquals(-2L, ((Reply.Int) r).value());
    }

    @Test
    void ttlNoExpiryReturnsMinusOne() {
        dispatcher.execute(store, "SET", List.of("k", "v"));
        Reply r = dispatcher.execute(store, "TTL", List.of("k"));
        assertEquals(-1L, ((Reply.Int) r).value());
    }

    @Test
    void flushAllClearsEverything() {
        dispatcher.execute(store, "SET", List.of("a", "1"));
        dispatcher.execute(store, "SET", List.of("b", "2"));
        Reply r = dispatcher.execute(store, "FLUSHALL", List.of());
        assertInstanceOf(Reply.Simple.class, r);
        assertEquals(0, store.size());
    }

    @Test
    void caseInsensitiveCommand() {
        Reply r = dispatcher.execute(store, "ping", List.of());
        assertInstanceOf(Reply.Simple.class, r);
    }

    @Test
    void setWithEx() {
        dispatcher.execute(store, "SET", List.of("k", "v", "EX", "60"));
        Reply t = dispatcher.execute(store, "TTL", List.of("k"));
        assertTrue(((Reply.Int) t).value() > 0);
    }
}
