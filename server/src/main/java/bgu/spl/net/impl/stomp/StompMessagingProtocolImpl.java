package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    
    private String loggedInUser = null; 
    private final Map<String, String> subIdToChannel = new HashMap<>();
    private final Database database; 
    private static final AtomicInteger messageIdCounter = new AtomicInteger(1);

    public StompMessagingProtocolImpl() {
        this.database = Database.getInstance();
    }

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }


    @Override
    public String process(String message) { //יושב על הפרוסס שהוספנו לסטומפ פרוטוקול
        StompFrame frame = StompFrame.parse(message); 
        
        // לוודא שהמשתמש מחובר לפני עיבוד ההודעה
        if (loggedInUser == null && !frame.getCommand().equals("CONNECT")) {
            sendError("Not logged in", "You must log in before sending commands.", frame);
            return null;
        }
        
        switch (frame.getCommand()) {
            case "CONNECT":
                handleConnect(frame);
                break;
            case "SUBSCRIBE":
                handleSubscribe(frame);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(frame);
                break;
            case "SEND":
                handleSend(frame);
                break;
            case "DISCONNECT":
                handleDisconnect(frame);
                break;
            default:
                sendError("Unknown command", "The server did not recognize the STOMP command.", frame);
                break;
        }
        return null;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    //-----STOMP Command Handlers-----

    private void handleConnect(StompFrame frame) {
        String version = frame.getHeader("accept-version");
        String host = frame.getHeader("host");
        String login = frame.getHeader("login");
        String passcode = frame.getHeader("passcode");

        if (version == null || host == null || login == null || passcode == null) {
            sendError("Malformed frame", "CONNECT frame is missing headers.", frame);
            return;
        }

        if (loggedInUser != null) {
            sendError("Already logged in", "The client is already logged in.", frame);
            return;
        }

        // נאמת את המשתמש (חדש/קיים/כבר מחובר/טעות בסיסמה)
        LoginStatus status = database.login(connectionId, login, passcode);

        if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY || status == LoginStatus.ADDED_NEW_USER) {
            loggedInUser = login;
            String response = "CONNECTED\nversion:1.2\n\n\u0000"; 
            connections.send(connectionId, response);
        } else if (status == LoginStatus.WRONG_PASSWORD) {
            sendError("Wrong password", "The password provided is incorrect.", frame); 
        } else if (status == LoginStatus.ALREADY_LOGGED_IN) {
            sendError("User already logged in", "This user has an active session elsewhere.", frame); 
        }
    }

    private void handleSubscribe(StompFrame frame) {
        String destination = frame.getHeader("destination");
        String id = frame.getHeader("id");

        if (destination == null || id == null) {
            sendError("Malformed frame", "SUBSCRIBE frame is missing destination or id.", frame);
            return;
        }

        // מיפוי מקומי ולבסיס הנתונים
        subIdToChannel.put(id, destination);
        database.subscribe(destination, connectionId, id);
        
        sendReceiptIfNeeded(frame);
    }

    private void handleUnsubscribe(StompFrame frame) {
        String id = frame.getHeader("id");

        if (id == null) {
            sendError("Malformed frame", "UNSUBSCRIBE missing id header.", frame);
            return;
        }

        String channel = subIdToChannel.remove(id);
        if (channel != null) {
            database.unsubscribe(channel, connectionId);
        } else {
            sendError("Invalid subscription", "No active subscription found for id " + id, frame);
            return;
        }
        
        sendReceiptIfNeeded(frame);
    }

    private void handleSend(StompFrame frame) {
        String destination = frame.getHeader("destination");
        String body = frame.getBody();

        if (destination == null) {
            sendError("Malformed frame", "SEND missing destination header.", frame);
            return;
        }

        // נוודא שהמשתמש רשום לערוץ שאליו הוא רוצה לשלוח
        if (!database.getChannelSubscribers(destination).containsKey(connectionId)) {
            sendError("Not subscribed", "You cannot send messages to a topic you are not subscribed to.", frame);
            return;
        }

        if (body.contains("event name")) { // בדיקה שיש תוכן בהודעה
            database.trackFileUpload(loggedInUser, "report_data", destination);
        }

        // שידור לכל הרשומים לערוץ
        ConcurrentHashMap<Integer, String> subscribers = database.getChannelSubscribers(destination);
        int msgId = messageIdCounter.getAndIncrement();

        for (Map.Entry<Integer, String> entry : subscribers.entrySet()) {
            int subConnectionId = entry.getKey();
            String subId = entry.getValue();

            // בניית הודעה ייחודית לכל משתמש
            String messageFrame = 
                "MESSAGE\n" +
                "subscription:" + subId + "\n" +
                "message-id:" + msgId + "\n" +
                "destination:" + destination + "\n" +
                "\n" + body + "\n\u0000";

            connections.send(subConnectionId, messageFrame);
        }
        
        sendReceiptIfNeeded(frame);
    }

    private void handleDisconnect(StompFrame frame) {
        String receiptId = frame.getHeader("receipt");
        
        if (receiptId != null) {
            sendReceiptIfNeeded(frame);
        }
        
        if (loggedInUser != null) {
            database.logout(connectionId);
        }
        
        connections.disconnect(connectionId);
        shouldTerminate = true; 
    }

    //-----Helper Methods------
    private void sendReceiptIfNeeded(StompFrame frame) {
        String receiptId = frame.getHeader("receipt");
        if (receiptId != null) {
            String response = "RECEIPT\nreceipt-id:" + receiptId + "\n\n\u0000";
            connections.send(connectionId, response);
        }
    }

    private void sendError(String messageHeader, String body, StompFrame causeFrame) {
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append("ERROR\n");
        
        String receiptId = causeFrame.getHeader("receipt");
        if (receiptId != null) {
            errorMsg.append("receipt-id:").append(receiptId).append("\n");
        }
        
        errorMsg.append("message:").append(messageHeader).append("\n\n");
        errorMsg.append(body).append("\n-----\n");
        errorMsg.append(causeFrame.toString()).append("\n\u0000"); 

        connections.send(connectionId, errorMsg.toString());
        connections.disconnect(connectionId);
        shouldTerminate = true;
    }
}