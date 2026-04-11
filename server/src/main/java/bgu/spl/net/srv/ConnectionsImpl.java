package bgu.spl.net.srv;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * מימוש ממשק Connections
 * מחזיק מיפוי של ID לHandler
 * */
public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי User ID -> Handler
    private final Map<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    //מיפוי Channel Name -> List of Users Subrcribed
    private final Map<String, Set<Integer>> channelSubscriptions = new ConcurrentHashMap<>();

    //מיפויים נוספים לטובת StompFrame
    // מיפוי Subscription ID -> (מיפוי Channel ID -> Channel Name) 
    private final Map<Integer, Map<Integer, String>> subIdToChannel = new ConcurrentHashMap<>();

    //מיפוי הפוך: Channel ID -> (מיפוי Channel Name -> Subscription ID)
    private final Map<Integer, Map<String, Integer>> channelToSubId = new ConcurrentHashMap<>();

    private final AtomicInteger messageIdCounter = new AtomicInteger(1);

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    public void addSubscription(int connectionId, int subId, String channel) {
        // מוסיף את הרישום החדש למפות הרלוונטיות
        channelSubscriptions.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        subIdToChannel.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>()).put(subId, channel);
        channelToSubId.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>()).put(channel, subId);
    }

    public void removeSubscription(int connectionId, int subId) {
        // הסרה מכל המפות הרלוונטיות ב O(1)
        Map<Integer, String> userSubs = subIdToChannel.get(connectionId);
        if (userSubs != null) {
            String channel = userSubs.remove(subId);
            if (channel != null) {
                Set<Integer> subscribers = channelSubscriptions.get(channel);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
                Map<String, Integer> userChannels = channelToSubId.get(connectionId);
                if (userChannels != null) {
                    userChannels.remove(channel);
                }
            }
        }
    }

    // אם המשתמש קיים ומחובר, נעביר את ההודעה שלו באמצעות ההאנדלר
    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg); 
            return true;
        }
        return false;
    }

    // אם הערוץ קיים (לפי שם), נעביר את ההודעה לכל המשתמשים הרשומים לערוץ
    @Override
    @SuppressWarnings("unchecked")
    public void send(String channel, T msg) {
        Set<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            for (Integer connId : subscribers) {
                Map<String, Integer> userChannels = channelToSubId.get(connId);
                if (userChannels != null) {
                    Integer subId = userChannels.get(channel);
                    if (subId != null) {
                        String messageFrame = "MESSAGE\n" +
                                "subscription:" + subId + "\n" +
                                "destination:" + channel + "\n" +
                                "message-id:" + messageIdCounter.getAndIncrement() + "\n\n" +
                                msg + "\u0000";
                        send(connId, (T) messageFrame);
                    }
                }
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        //ניקוי רישומים מכל הערוצים כדי למנוע דליפות זיכרון
        subIdToChannel.remove(connectionId);
        Map<String, Integer> userChannels = channelToSubId.remove(connectionId);
        if (userChannels != null) {
            for (String channel : userChannels.keySet()) {
                Set<Integer> subscribers = channelSubscriptions.get(channel);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
            }
        }

        ConnectionHandler<T> handler = activeConnections.remove(connectionId);
        if (handler != null) {
            try {
                handler.close();
            } catch (IOException ignored) {}
        }
    }
}