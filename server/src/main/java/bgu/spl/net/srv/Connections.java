package bgu.spl.net.srv;

import java.io.IOException;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);

    //הפונקציות החדשות כדי שהפרוטוקל שלנו יוכל להשתמש בהם בלי castings מיותרים
    void addConnection(int connectionId, ConnectionHandler<T> handler);

    void subscribe(String channel, int connectionId);

    void unsubscribe(String channel, int connectionId);
}
