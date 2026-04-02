package bgu.spl.net.srv;

//ממשק חיבורים ע"פ ההגדרה
public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);
}