package com.goatmosire.mcp;

import com.goatmosire.service.MapService;
import com.gsim.mcp.AbstractMcpServer;
import com.gsim.mcp.GsimMcpToolRegistry;
import com.gsim.mcp.McpToolRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GoatMosire MCP (Model Context Protocol) server over stdio.
 *
 * <p>Extends {@link AbstractMcpServer} for JSON-RPC 2.0 protocol handling.
 * Merges both GoatMosire map tools ({@code goatmosire_*}) and GSim world/document
 * management tools ({@code gsim_*}) via the standard {@link McpToolRegistry} interface.
 *
 * <p>All GoatMosire tools are prefixed "goatmosire_", GSim tools prefixed "gsim_".
 */
public class McpServer extends AbstractMcpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final McpToolRegistry goatRegistry;
    private final McpToolRegistry gsimRegistry;

    /**
     * Creates an MCP server with both GoatMosire and GSim tool registries.
     *
     * @param mapService the shared map service instance
     * @param importDir  directory for importing GSim worlds
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // MapService is a shared service class, not a data object
    public McpServer(MapService mapService, Path importDir) {
        super(List.of()); // registries set below after construction
        this.goatRegistry = new com.goatmosire.mcp.McpToolRegistry(mapService);
        GsimMcpToolRegistry rawGsim = new GsimMcpToolRegistry(mapService.getWorldsDir(), importDir, null);
        this.gsimRegistry = rawGsim.asMcpRegistry();
    }

    // ── AbstractMcpServer template methods ──────────────────

    @Override
    protected String getServerName() {
        return "GoatMosire";
    }

    @Override
    protected String getServerVersion() {
        return "0.1.0";
    }

    @Override
    protected List<com.gsim.mcp.ToolDef> getAllTools() {
        List<com.gsim.mcp.ToolDef> goat = goatRegistry.all();
        List<com.gsim.mcp.ToolDef> gsim = gsimRegistry.all();
        List<com.gsim.mcp.ToolDef> all = new java.util.ArrayList<>(goat);
        all.addAll(gsim);
        log.info("tools/list: {} goatmosire + {} gsim = {} total", goat.size(), gsim.size(), all.size());
        return all;
    }

    @Override
    protected String executeTool(String name, com.fasterxml.jackson.databind.JsonNode args) throws Exception {
        // Route: gsim_* tools go to GSim registry, goatmosire_* to GoatMosire
        if (name.startsWith("gsim_")) {
            return gsimRegistry.execute(name, args);
        }
        return goatRegistry.execute(name, args);
    }

    // ── Lifecycle ───────────────────────────────────────────

    /**
     * Signals the server loop to stop and closes stdin.
     */
    @Override
    public void stop() {
        log.info("[MCP-LIFECYCLE] GoatMosire MCP server stop requested");
        super.stop();
    }
}
