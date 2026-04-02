package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;

public class StompFrame {
    private String command;
    private final Map<String, String> headers;
    private String body;

    public StompFrame(String command) {
        this.command = command;
        this.headers = new HashMap<>();
        this.body = "";
    }

    // --- Getters and Setters ---
    public String getCommand() { return command; }
    public String getHeader(String key) { return headers.get(key); }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
    
    public void addHeader(String key, String value) { 
        headers.put(key, value); 
    }
    public void setBody(String body) { 
        this.body = body; 
    }

    /**
     * Parses a raw STOMP string into a StompFrame object.
     */
    public static StompFrame parse(String message) {
        // Strip the trailing null character if the MessageEncoderDecoder left it attached
        if (message.endsWith("\u0000")) {
            message = message.substring(0, message.length() - 1);
        }

        String[] lines = message.split("\n");
        if (lines.length == 0) {
            return new StompFrame("UNKNOWN");
        }

        // 1. The first line is always the STOMP command
        StompFrame frame = new StompFrame(lines[0].trim());
        int i = 1;

        // 2. Parse the headers until we hit an empty line
        while (i < lines.length && !lines[i].trim().isEmpty()) {
            String line = lines[i];
            int colonIndex = line.indexOf(':');
            if (colonIndex != -1) {
                // Split by the first colon to get key and value
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                frame.addHeader(key, value);
            }
            i++;
        }

        // 3. Skip the empty line
        i++; 

        // 4. Everything else is the body
        StringBuilder bodyBuilder = new StringBuilder();
        while (i < lines.length) {
            bodyBuilder.append(lines[i]);
            if (i < lines.length - 1) {
                bodyBuilder.append("\n"); // Preserve line breaks in the body
            }
            i++;
        }
        frame.setBody(bodyBuilder.toString());

        return frame;
    }

    /**
     * Converts the StompFrame back into a raw STOMP string so it can be sent over the socket.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append("\n");
        
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
        }
        
        sb.append("\n"); // Blank line separating headers and body
        
        if (body != null && !body.isEmpty()) {
            sb.append(body).append("\n");
        }
        
        sb.append("\u0000"); // Frame MUST end with a null character
        return sb.toString();
    }
}