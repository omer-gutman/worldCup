package bgu.spl.net.srv;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי: Connection ID -> ConnectionHandler
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    // מיפוי: Channel -> Set of connection IDs that subscribe to the channel
    private final ConcurrentHashMap<String, Set<Integer>> channelSubscribers = new ConcurrentHashMap<>();

    //מיפוי הפוך: Connection ID -> Set of Channels its subscribed to
    //מאפשר מחיקה ב-O(1) בזמן ניתוק
    private final ConcurrentHashMap<Integer, Set<String>> clientChannels = new ConcurrentHashMap<>();

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
        Set<Integer> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            for (Integer connectionId : subscribers) {
                //שליחת הודעה בצורה כללית כדי שתתאים לכל פרוטוקול
                //ספסיפיקציה תמומש בתוך מימוש STOMP
                send(connectionId, msg); 
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        activeConnections.remove(connectionId);
        
        // שליפת הרשימה מהמפה ההפוכה כדי לנתק לקוח ב-O(1)
        Set<String> channels = clientChannels.remove(connectionId);
        
        if (channels != null) {
            for (String channel : channels) {
                Set<Integer> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
            }
        }
    }
    //מתודה עוזרת שנועדה להוסיף חיבור חדש לאוסף החיבורים
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }   
}