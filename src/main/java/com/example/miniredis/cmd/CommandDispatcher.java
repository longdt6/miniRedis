package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CommandDispatcher {

    private Map<String, CommandHandler> dispatch = Map.of();

    @PostConstruct
    void init() {
        Map<String, CommandHandler> map = new HashMap<>();
        map.put("PING", StringCommands::ping);
        map.put("SET", StringCommands::set);
        map.put("GET", StringCommands::get);
        map.put("DEL", StringCommands::del);
        map.put("EXISTS", StringCommands::exists);
        map.put("INCR", StringCommands::incr);
        map.put("KEYS", StringCommands::keys);
        map.put("FLUSHALL", StringCommands::flushAll);
        map.put("EXPIRE", ExpiryCommands::expire);
        map.put("TTL", ExpiryCommands::ttl);
        map.put("PEXPIREAT", ExpiryCommands::pexpireat);
        dispatch = Map.copyOf(map);
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
