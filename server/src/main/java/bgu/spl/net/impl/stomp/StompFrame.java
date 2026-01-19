package bgu.spl.net.impl.stomp;

import java.util.Map;
import java.util.HashMap;
// אנחנו מדברים פה על הdata structure הכי חשוב לשרת שלנו - הStompFrame שמייצג מסגרת STOMP
// מתרגם מהמידע השנכנס מהלקוח למשהו שנוח לעבוד איתו בשרת
public class StompFrame {
    // הפקודה של המסגרת (CONNECT, SEND, SUBSCRIBE וכו')
    private String command;
    // המפות של הכותרות (headers) והגוף (body) של המסגרת
    // כותרת לדוגמא ״destination:/topic/sports״
    // גוף לדוגמא ״מסי הביא גול!״
    private Map<String, String> headers = new HashMap<>();
    private String body;

    

    public StompFrame(String command) {
        this.command = command;
    }

    // המתודות הגישה (getters ו-setters)
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public Map<String, String> getHeaders() { return headers; }
    public void addHeader(String key, String value) { headers.put(key, value); }
    
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    // המתודה שממירה את המסגרת למחרוזת בפורמט STOMP תקני
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // 1. הוספת הפקודה  
        sb.append(command).append("\n");
        
        // 2. הוספת הכותרות
        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append(header.getKey()).append(":").append(header.getValue()).append("\n");
        }
        
        sb.append("\n"); // הוספת שורה ריקה בין הכותרות לגוף
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }
        sb.append("\u0000"); // הtermination null character
        return sb.toString();
    }
}