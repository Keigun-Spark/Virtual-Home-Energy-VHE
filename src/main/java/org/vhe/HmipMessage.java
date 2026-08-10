package org.vhe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a standard WebSocket message used by the Homematic IP Connect API.
 * Uses Jackson's JsonNode to dynamically capture arbitrary payload bodies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HmipMessage {
    private String id;
    private String type;
    private String pluginId;
    private JsonNode body;

    public HmipMessage() {
    }

    public HmipMessage(String id, String type, String pluginId, JsonNode body) {
        this.id = id;
        this.type = type;
        this.pluginId = pluginId;
        this.body = body;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public JsonNode getBody() {
        return body;
    }

    public void setBody(JsonNode body) {
        this.body = body;
    }
}
