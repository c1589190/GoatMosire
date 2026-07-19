package com.goatmosire.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read/write GSim node checkpoints (narrative, factions, worldview, characters, map).
 */
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final Path worldsDir;

    /**
     * Constructs a checkpoint service for the given worlds directory.
     * @param worldsDir the base directory containing world node data
     */
    public CheckpointService(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    /** Path to a node JSON file. */
    private Path nodePath(String worldId, String nodeId) {
        return worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
    }

    /** Read the entire node JSON document. */
    private ObjectNode readNode(String worldId, String nodeId) throws IOException {
        Path path = nodePath(worldId, nodeId);
        if (!Files.exists(path)) throw new IllegalArgumentException("Node not found: " + worldId + "/" + nodeId);
        JsonNode parsed = MAPPER.readTree(path.toFile());
        if (parsed == null) throw new IOException("Failed to parse node file (null result): " + path);
        return (ObjectNode) parsed;
    }

    /** Write back the entire node JSON document. */
    private void writeNode(String worldId, String nodeId, ObjectNode node) throws IOException {
        Path path = nodePath(worldId, nodeId);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node);
    }

    private String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    // ── Public API ───────────────────────────────────────

    /**
     * List all checkpoint names in a node.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @return map with worldId, nodeId, and a list of checkpoints
     * @throws IOException if the node file cannot be read
     */
    public Map<String, Object> listCheckpoints(String worldId, String nodeId) throws IOException {
        if (worldId == null || nodeId == null)
            throw new IllegalArgumentException("worldId and nodeId must not be null");
        ObjectNode node = readNode(worldId, nodeId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", worldId);
        result.put("nodeId", nodeId);

        List<Map<String, Object>> checkpoints = new ArrayList<>();
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps != null) {
            var fields = cps.fields();
            while (fields.hasNext()) {
                var f = fields.next();
                Map<String, Object> cp = new LinkedHashMap<>();
                cp.put("name", f.getKey());
                var cpNode = f.getValue();
                cp.put("label", cpNode.has("label") ? cpNode.get("label").asText() : "");
                cp.put("type", cpNode.has("type") ? cpNode.get("type").asText() : "");
                cp.put(
                        "elementCount",
                        cpNode.has("elements") ? cpNode.get("elements").size() : 0);
                checkpoints.add(cp);
            }
        }
        result.put("checkpoints", checkpoints);
        return result;
    }

    /**
     * Get elements from a checkpoint, optionally filtered by tags and/or key.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param checkpointName the checkpoint name
     * @param filterKey optional key filter (null to skip)
     * @param filterTags optional tag filter (null to skip)
     * @return map with checkpoint elements and metadata
     * @throws IOException if the node file cannot be read
     */
    public Map<String, Object> getCheckpoint(
            String worldId, String nodeId, String checkpointName, String filterKey, List<String> filterTags)
            throws IOException {
        ObjectNode node = readNode(worldId, nodeId);
        JsonNode cps = node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointName);

        JsonNode cp = cps.get(checkpointName);
        JsonNode elements = cp.get("elements");
        if (elements == null || !elements.isArray())
            return Map.of(
                    "worldId",
                    worldId,
                    "nodeId",
                    nodeId,
                    "checkpoint",
                    checkpointName,
                    "elements",
                    List.of(),
                    "count",
                    0);

        List<Object> filtered = new ArrayList<>();
        for (JsonNode el : elements) {
            // Filter by key if specified
            if (filterKey != null && !filterKey.isEmpty()) {
                String ek = el.has("key") ? el.get("key").asText() : "";
                if (!ek.equals(filterKey)) continue;
            }
            // Filter by tags if specified (must have ALL specified tags)
            if (filterTags != null && !filterTags.isEmpty()) {
                Set<String> elTags = new HashSet<>();
                if (el.has("tags") && el.get("tags").isArray()) {
                    for (JsonNode t : el.get("tags")) {
                        elTags.add(t.asText());
                    }
                }
                if (!elTags.containsAll(filterTags)) continue;
            }
            filtered.add(MAPPER.treeToValue(el, Map.class));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", worldId);
        result.put("nodeId", nodeId);
        result.put("checkpoint", checkpointName);
        result.put("label", cp.has("label") ? cp.get("label").asText() : "");
        result.put("elements", filtered);
        result.put("count", filtered.size());
        return result;
    }

    /**
     * Add an element to a checkpoint. Creates the element with the given key/type/value/tags.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param checkpointName the checkpoint name
     * @param key element key
     * @param type element type
     * @param value element value
     * @param tags element tags
     * @return map with operation result
     * @throws IOException if the node file cannot be read or written
     */
    public Map<String, Object> addElement(
            String worldId,
            String nodeId,
            String checkpointName,
            String key,
            String type,
            String value,
            List<String> tags)
            throws IOException {
        ObjectNode node = readNode(worldId, nodeId);
        ObjectNode cps = getOrCreateCheckpoints(node);
        ObjectNode cp = getOrCreateCheckpoint(cps, checkpointName);
        ArrayNode elements = getOrCreateElements(cp);

        // Check for duplicate key
        for (JsonNode el : elements) {
            if (el.has("key") && el.get("key").asText().equals(key)) {
                throw new IllegalArgumentException(
                        "Element with key '" + key + "' already exists in " + checkpointName);
            }
        }

        ObjectNode el = MAPPER.createObjectNode();
        el.put("key", key);
        el.put("type", type != null ? type : "text");
        el.put("value", value != null ? value : "");
        ArrayNode tagArr = MAPPER.createArrayNode();
        if (tags != null) {
            for (String t : tags) {
                tagArr.add(t);
            }
        }
        el.set("tags", tagArr);
        el.set("links", MAPPER.createArrayNode());
        String ts = now();
        el.put("createdAt", ts);
        el.put("updatedAt", ts);
        elements.add(el);

        writeNode(worldId, nodeId, node);
        log.info("Added element '{}' to checkpoint '{}' in {}/{}", key, checkpointName, worldId, nodeId);

        return Map.of(
                "ok",
                true,
                "worldId",
                worldId,
                "nodeId",
                nodeId,
                "checkpoint",
                checkpointName,
                "key",
                key,
                "type",
                el.get("type").asText());
    }

    /**
     * Update an existing element's value, tags, or type.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param checkpointName the checkpoint name
     * @param key element key to update
     * @param value new value (null to keep unchanged)
     * @param type new type (null to keep unchanged)
     * @param tags new tags (null to keep unchanged)
     * @return map with operation result
     * @throws IOException if the node file cannot be read or written
     */
    public Map<String, Object> updateElement(
            String worldId,
            String nodeId,
            String checkpointName,
            String key,
            String value,
            String type,
            List<String> tags)
            throws IOException {
        ObjectNode node = readNode(worldId, nodeId);
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointName);

        ArrayNode elements = (ArrayNode) cps.get(checkpointName).get("elements");
        if (elements == null) throw new IllegalArgumentException("No elements in checkpoint: " + checkpointName);

        ObjectNode target = null;
        for (JsonNode el : elements) {
            if (el.has("key") && el.get("key").asText().equals(key)) {
                target = (ObjectNode) el;
                break;
            }
        }
        if (target == null) throw new IllegalArgumentException("Element not found: " + key + " in " + checkpointName);

        if (value != null) target.put("value", value);
        if (type != null) target.put("type", type);
        if (tags != null) {
            ArrayNode tagArr = MAPPER.createArrayNode();
            for (String t : tags) {
                tagArr.add(t);
            }
            target.set("tags", tagArr);
        }
        target.put("updatedAt", now());

        writeNode(worldId, nodeId, node);
        log.info("Updated element '{}' in checkpoint '{}' in {}/{}", key, checkpointName, worldId, nodeId);

        return Map.of("ok", true, "worldId", worldId, "nodeId", nodeId, "checkpoint", checkpointName, "key", key);
    }

    /**
     * Delete an element from a checkpoint by key.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param checkpointName the checkpoint name
     * @param key element key to delete
     * @return map with operation result
     * @throws IOException if the node file cannot be read or written
     */
    public Map<String, Object> deleteElement(String worldId, String nodeId, String checkpointName, String key)
            throws IOException {
        ObjectNode node = readNode(worldId, nodeId);
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointName);

        ArrayNode elements = (ArrayNode) cps.get(checkpointName).get("elements");
        if (elements == null) throw new IllegalArgumentException("No elements in checkpoint: " + checkpointName);

        int idx = -1;
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).has("key")
                    && elements.get(i).get("key").asText().equals(key)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) throw new IllegalArgumentException("Element not found: " + key + " in " + checkpointName);

        elements.remove(idx);
        writeNode(worldId, nodeId, node);
        log.info("Deleted element '{}' from checkpoint '{}' in {}/{}", key, checkpointName, worldId, nodeId);

        return Map.of("ok", true, "worldId", worldId, "nodeId", nodeId, "checkpoint", checkpointName, "key", key);
    }

    /**
     * Find all checkpoint elements referencing oldName and update to newName.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param oldName the name to replace
     * @param newName the replacement name
     * @return the number of updated elements
     * @throws IOException if the node file cannot be read or written
     */
    public int renameReferences(String worldId, String nodeId, String oldName, String newName) throws IOException {
        ObjectNode node = readNode(worldId, nodeId);
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps == null) return 0;

        int updated = 0;
        var fields = cps.fields();
        while (fields.hasNext()) {
            var f = fields.next();
            JsonNode cpNode = f.getValue();
            if (!cpNode.has("elements") || !cpNode.get("elements").isArray()) continue;

            ArrayNode elements = (ArrayNode) cpNode.get("elements");
            for (JsonNode el : elements) {
                ObjectNode elObj = (ObjectNode) el;
                boolean changed = false;

                // Update key if it contains the old region name
                if (elObj.has("key")) {
                    String key = elObj.get("key").asText();
                    // Match exact key or key patterns like "Nation:大汉", "大汉开局", "City:洛阳"
                    if (key.equals(oldName)) {
                        elObj.put("key", newName);
                        changed = true;
                    } else if (key.startsWith(oldName + ":") || key.endsWith(":" + oldName)) {
                        elObj.put("key", key.replace(oldName, newName));
                        changed = true;
                    }
                }

                // Update tags that contain the old region name
                if (elObj.has("tags") && elObj.get("tags").isArray()) {
                    ArrayNode tags = (ArrayNode) elObj.get("tags");
                    for (int i = 0; i < tags.size(); i++) {
                        if (tags.get(i).asText().equals(oldName)) {
                            tags.set(i, newName);
                            changed = true;
                        }
                    }
                }

                // Update value text references
                if (elObj.has("value")) {
                    String val = elObj.get("value").asText();
                    if (val.contains(oldName)) {
                        elObj.put("value", val.replace(oldName, newName));
                        changed = true;
                    }
                }

                if (changed) {
                    elObj.put("updatedAt", now());
                    updated++;
                }
            }
        }

        if (updated > 0) {
            writeNode(worldId, nodeId, node);
            log.info(
                    "Renamed '{}' -> '{}' in {} checkpoint elements across {}/{}",
                    oldName,
                    newName,
                    updated,
                    worldId,
                    nodeId);
        }
        return updated;
    }

    // ── Helpers ──────────────────────────────────────────

    private ObjectNode getOrCreateCheckpoints(ObjectNode node) {
        if (!node.has("checkpoints") || node.get("checkpoints").isNull()) {
            node.set("checkpoints", MAPPER.createObjectNode());
        }
        return (ObjectNode) node.get("checkpoints");
    }

    private ObjectNode getOrCreateCheckpoint(ObjectNode cps, String name) {
        if (!cps.has(name) || cps.get(name).isNull()) {
            ObjectNode cp = MAPPER.createObjectNode();
            cp.put("label", name);
            cp.put("type", "misc");
            cp.set("elements", MAPPER.createArrayNode());
            cps.set(name, cp);
        }
        return (ObjectNode) cps.get(name);
    }

    private ArrayNode getOrCreateElements(ObjectNode cp) {
        if (!cp.has("elements") || cp.get("elements").isNull()) {
            cp.set("elements", MAPPER.createArrayNode());
        }
        return (ArrayNode) cp.get("elements");
    }
}
