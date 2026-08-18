package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;

import java.util.List;

@FunctionalInterface
public interface CommandHandler {
    Reply execute(Store store, List<String> args);
}
