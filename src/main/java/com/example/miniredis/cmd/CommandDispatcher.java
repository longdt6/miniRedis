package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CommandDispatcher {

    private final Map<String, CommandHandler> dispatch = new HashMap<>();

    @PostConstruct
    void init() {
        dispatch.put("PING", StringCommands::ping);
        dispatch.put("SET", StringCommands::set);
        dispatch.put("GET", StringCommands::get);
        dispatch.put("DEL", StringCommands::del);
        dispatch.put("EXISTS", StringCommands::exists);
        dispatch.put("INCR", StringCommands::incr);
        dispatch.put("KEYS", StringCommands::keys);
        dispatch.put("FLUSHALL", StringCommands::flushAll);
        dispatch.put("EXPIRE", ExpiryCommands::expire);
        dispatch.put("TTL", ExpiryCommands::ttl);
    }

    public Reply execute(Store store, String name, List<String> args) {
        if (name == null) {
            return new Reply.Error("ERR empty command");
        }
        CommandHandler h = dispatch.get(name.toUpperCase());
        if (h == null) {
            return new Reply.Error("ERR unknown command '" + name + "'");
        }
        try {
            return h.execute(store, args);
        } catch (NumberFormatException e) {
            return new Reply.Error("ERR value is not an integer or out of range");
        }
    }
}
