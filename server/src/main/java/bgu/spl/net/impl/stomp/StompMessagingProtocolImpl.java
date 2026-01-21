package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<StompFrame> {

    private int connectionId;
    private Connections<StompFrame> connections;
    private boolean shouldTerminate = false;
    
    // מיפוי בין Subscription ID (שהלקוח שלח) לבין שם הערוץ (Topic)
    // מפתח: ID, ערך: Channel Name
    private Map<String, String> subscriptionIdToChannel = new HashMap<>();

    @Override
    public void start(int connectionId, Connections<StompFrame> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(StompFrame message) {
        String command = message.getCommand();

        switch (command) {
            case "CONNECT":
                handleConnect(message);
                break;
            case "SUBSCRIBE":
                handleSubscribe(message);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(message);
                break;
            case "SEND":
                handleSend(message);
                break;
            case "DISCONNECT":
                handleDisconnect(message);
                break;
            default:
                // ניתן להוסיף טיפול בשגיאה על פקודה לא מוכרת או להתעלם
                break;
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // --- Private Handler Methods ---

    private void handleConnect(StompFrame message) {
        // בגרסה הסופית נבדוק כאן שם משתמש וסיסמה
        String login = message.getHeaders().get("login");
        String passcode = message.getHeaders().get("passcode");

        StompFrame connected = new StompFrame("CONNECTED");
        connected.addHeader("version", "1.2");
        connections.send(connectionId, connected);
    }

    private void handleSubscribe(StompFrame message) {
        String destination = message.getHeaders().get("destination");
        String id = message.getHeaders().get("id");

        if (destination != null && id != null) {
            // 1. שמירת המיפוי אצלנו בפרוטוקול כדי שנוכל לבטל אח"כ
            subscriptionIdToChannel.put(id, destination);
            
            // 2. רישום הלקוח ב-Connections הראשי
            connections.subscribe(destination, connectionId);
        }

        // שליחת אישור (Receipt) אם התבקש
        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleUnsubscribe(StompFrame message) {
        String id = message.getHeaders().get("id");
        
        // שליפת הערוץ המתאים ל-ID הזה
        String destination = subscriptionIdToChannel.remove(id);
        
        if (destination != null) {
            // ביטול הרישום ב-Connections
            connections.unsubscribe(destination, connectionId);
        }

        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleSend(StompFrame message) {
        String destination = message.getHeaders().get("destination");
        
        if (destination != null) {
            // יצירת הודעת MESSAGE שתשלח לכל המנויים
            StompFrame msgToSend = new StompFrame("MESSAGE");
            msgToSend.addHeader("destination", destination);
            msgToSend.addHeader("message-id", "msg-" + System.currentTimeMillis()); // ID ייחודי
            // שים לב: header 'subscription' צריך להתווסף דינמית לכל לקוח שמקבל את ההודעה.
            // כרגע נשלח את הגוף כמו שהוא
            msgToSend.setBody(message.getBody());

            connections.send(destination, msgToSend);
        }

        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleDisconnect(StompFrame message) {
        // קודם שולחים אישור אם צריך
        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
        
        // ואז מסמנים לסגירה ומנתקים
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private void sendReceipt(String receiptId) {
        StompFrame receipt = new StompFrame("RECEIPT");
        receipt.addHeader("receipt-id", receiptId);
        connections.send(connectionId, receipt);
    }
}