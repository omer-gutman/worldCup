package bgu.spl.net.srv;

import bgu.spl.net.impl.stomp.StompFrame;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי בין מזהה חיבור (ID) לבין ה-Handler שלו
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    // מיפוי בין שם ערוץ (Topic) לרשימה של מזהי חיבורים הרשומים אליו
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Integer>> channelSubscriptions = new ConcurrentHashMap<>();

    // מיפוי שמחזיק לכל לקוח (ConnectionID) מפה של <ChannelName, SubscriptionID>
    private final ConcurrentHashMap<Integer, Map<String, String>> clientSubscriptionIds = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        CopyOnWriteArrayList<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            for (Integer connectionId : subscribers) {
                // בדיקה שהלקוח עדיין מחובר
                if (activeConnections.containsKey(connectionId)) {
                    T msgToSend = msg;

                    // טיפול ספציפי ב-StompFrame כדי להוסיף את ה-subscription id
                    if (msg instanceof StompFrame) {
                        StompFrame originalFrame = (StompFrame) msg;
                        // חיפוש ה-Subscription ID של הלקוח הזה לערוץ הזה
                        Map<String, String> subs = clientSubscriptionIds.get(connectionId);
                        if (subs != null && subs.containsKey(channel)) {
                            String subId = subs.get(channel);
                            // שכפול ההודעה והוספת ה-header
                            StompFrame clientFrame = cloneFrame(originalFrame);
                            clientFrame.addHeader("subscription", subId);
                            msgToSend = (T) clientFrame;
                        }
                    }
                    
                    send(connectionId, msgToSend);
                }
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        activeConnections.remove(connectionId);
        clientSubscriptionIds.remove(connectionId);
        
        // הסרת הלקוח מכל הערוצים
        for (CopyOnWriteArrayList<Integer> subscribers : channelSubscriptions.values()) {
            subscribers.remove(Integer.valueOf(connectionId));
        }
    }

    @Override
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    @Override
    public void subscribe(String channel, int connectionId, String subscriptionId) {
        // הוספה לרשימת המנויים של הערוץ
        channelSubscriptions.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(connectionId);
        
        // שמירת ה-ID הספציפי של הלקוח לערוץ הזה
        clientSubscriptionIds.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>()).put(channel, subscriptionId);
    }

    @Override
    public void unsubscribe(String channel, int connectionId) {
        CopyOnWriteArrayList<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            subscribers.remove(Integer.valueOf(connectionId));
        }
        
        Map<String, String> subs = clientSubscriptionIds.get(connectionId);
        if (subs != null) {
            subs.remove(channel);
        }
    }
    
    // פונקציית עזר לשכפול Frame (Deep Copy רדוד לתוכן, עמוק להדרים)
    private StompFrame cloneFrame(StompFrame frame) {
        StompFrame newFrame = new StompFrame(frame.getCommand());
        if (frame.getHeaders() != null) {
            for (Map.Entry<String, String> entry : frame.getHeaders().entrySet()) {
                newFrame.addHeader(entry.getKey(), entry.getValue());
            }
        }
        newFrame.setBody(frame.getBody());
        return newFrame;
    }
}