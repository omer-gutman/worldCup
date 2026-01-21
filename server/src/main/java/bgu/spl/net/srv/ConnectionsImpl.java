package bgu.spl.net.srv;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי בין מזהה חיבור (ID) לבין ה-Handler שלו (כדי לשלוח הודעות ישירות)
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    // מיפוי בין שם ערוץ (Topic) לרשימה של מזהי חיבורים הרשומים אליו
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Integer>> channelSubscriptions = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg); // שליחה פיזית דרך ה-Handler
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        // שליחת הודעה לכל המנויים בערוץ מסוים
        CopyOnWriteArrayList<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            for (Integer connectionId : subscribers) {
                // חשוב: לבדוק שהלקוח עדיין מחובר לפני השליחה
                if (activeConnections.containsKey(connectionId)) {
                    send(connectionId, msg);
                }
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        // מחיקת הלקוח מהרשימה הראשית
        activeConnections.remove(connectionId);
        
        // אופציונלי: מחיקת הלקוח מכל הערוצים (אפשר גם להשאיר והוא פשוט לא יקבל הודעות)
        // אבל נקי יותר להסיר אותו:
        for (CopyOnWriteArrayList<Integer> subscribers : channelSubscriptions.values()) {
            subscribers.remove(Integer.valueOf(connectionId));
        }
    }

    // --- פונקציות עזר שנוסיף כדי שהפרוטוקול והשרת יוכלו לעבוד ---

    /**
     * הוספת חיבור חדש (ייקרא ע"י השרת כשהלקוח מתחבר)
     */
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    /**
     * הרשמה לערוץ (ייקרא ע"י הפרוטוקול בפקודת SUBSCRIBE)
     */
    public void subscribe(String channel, int connectionId) {
        // שימוש ב-computeIfAbsent כדי ליצור את הרשימה אם היא לא קיימת (Thread-safe)
        channelSubscriptions.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(connectionId);
    }

    /**
     * ביטול הרשמה לערוץ (ייקרא ע"י הפרוטוקול בפקודת UNSUBSCRIBE)
     */
    public void unsubscribe(String channel, int connectionId) {
        CopyOnWriteArrayList<Integer> subscribers = channelSubscriptions.get(channel);
        if (subscribers != null) {
            subscribers.remove(Integer.valueOf(connectionId));
        }
    }
}