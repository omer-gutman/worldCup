package bgu.spl.net.srv;

import java.io.IOException;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);

    // הוספת חיבור חדש (ייקרא ע"י השרת כשהלקוח מתחבר)
    void addConnection(int connectionId, ConnectionHandler<T> handler);

    // עדכון החתימה לקבלת subscriptionId
    void subscribe(String channel, int connectionId, String subscriptionId);

    void unsubscribe(String channel, int connectionId);
}