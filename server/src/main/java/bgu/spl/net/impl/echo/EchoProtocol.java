package bgu.spl.net.impl.echo;

import bgu.spl.net.api.StompMessagingProtocol; // Use the NEW interface
import bgu.spl.net.srv.Connections;
//שינינו את הדוגמה כדי שתתאים לפרוטוקול החדש
public class EchoProtocol implements StompMessagingProtocol<String> {
    //מוסיפים שדות שרלוונטיים לconnections
    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;

    //מוסיפים סטארט כי עד עכשיו לא היה צורך
    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }
    //process השתנה לvoid, כי שולחים הודעה ולא מחזירים הודעה.
    @Override
    public void process(String msg) {
        if (msg.equals("..quit")) {
            shouldTerminate = true;
            return;
        }
        // Instead of 'return msg', we use the new way:
        connections.send(connectionId, msg); 
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}