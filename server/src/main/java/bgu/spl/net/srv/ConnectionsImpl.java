package bgu.spl.net.srv;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * מימוש ממשק Connections
 * מחזיק מיפוי של ID לHandler
 * 
 */
public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי ID -> Handler
    private final Map<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    //מיפוי channel name -> List of users subrcribed
    private final Map<String, Set<Integer>> channelSubscriptions = new ConcurrentHashMap<>();


    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    public void subscribe(String channel, int connectionId) {
        channelSubscriptions.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(connectionId);
    }

    public void unsubscribe(String channel, int connectionId) {
        Set<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            subscribers.remove(connectionId);
        }
    }

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
        Set<Integer> subscribers = channelSubscriptions.getOrDefault(channel, Collections.emptySet());
        for (Integer connectionId : subscribers) {
            send(connectionId, msg);
        }
    }

    @Override
    public void disconnect(int connectionId) {
        
        ConnectionHandler<T> handler = activeConnections.remove(connectionId);
        
        if (handler != null) {
            try {
                handler.close();
            } catch (IOException ignored) {
                // שגיאה בסגירה לא אמורה לעצור את תהליך הניקוי
            }
        }

        //ניקוי רישומים מכל הערוצים כדי למנוע דליפות זיכרון
        for (Set<Integer> subscribers : channelSubscriptions.values()) {
            subscribers.remove(connectionId);
        }
    }
}