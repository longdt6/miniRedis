package com.example.miniredis.web;

import com.example.miniredis.cmd.CommandDispatcher;
import com.example.miniredis.cmd.Reply;
import com.example.miniredis.store.Store;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/cmd")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CmdResource {

    @Inject
    Store store;

    @Inject
    CommandDispatcher dispatcher;

    @POST
    public ReplyWriter.ReplyDto handle(CmdRequest req) {
        if (req == null) {
            return ReplyWriter.toDto(new Reply.Error("ERR empty body"));
        }
        Reply r = dispatcher.execute(store, req.cmd(), req.args() == null ? List.of() : req.args());
        return ReplyWriter.toDto(r);
    }

    public record CmdRequest(String cmd, List<String> args) {
    }
}
