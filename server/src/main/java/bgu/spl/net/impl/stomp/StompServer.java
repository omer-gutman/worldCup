package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;
import bgu.spl.net.srv.ConnectionsImpl;
import bgu.spl.net.api.StompMessagingProtocol;
import java.util.concurrent.atomic.AtomicInteger;

public class StompServer {
    
    // מונה גלובלי לכל השרת
    private static final AtomicInteger connectionIdCounter = new AtomicInteger(0);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: port server_type(tpc/reactor)");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        ConnectionsImpl<StompFrame> connections = new ConnectionsImpl<>();
        UserController userController = new UserController();

        if (serverType.equals("tpc")) {
             new BaseServer<StompFrame>(
                    port,
                    () -> new StompMessagingProtocolImpl(connections, userController),
                    StompEncoderDecoder::new
            ) {
                @Override
                protected void execute(BlockingConnectionHandler<StompFrame> handler) {
                    // 1. יצירת ID חדש ללקוח
                    int id = connectionIdCounter.incrementAndGet();
                    
                    // 2. שליפת הפרוטוקול ואתחולו עם ה-ID וה-Connections
                    // (אנחנו יודעים בוודאות שזה StompMessagingProtocol בגלל ה-Factory למעלה)
                    StompMessagingProtocol<StompFrame> protocol = (StompMessagingProtocol<StompFrame>) handler.getProtocol();
                    protocol.start(id, connections);
                    
                    // 3. רישום ההנדלר ב-Connections כדי שיוכל לקבל הודעות
                    connections.addConnection(id, handler); 
                    
                    // 4. הפעלת ה-Thread
                    new Thread(handler).start();
                }
            }.serve();

        } else if (serverType.equals("reactor")) {
            // הערה: כדי שה-Reactor יעבוד, צריך לבצע שינויים דומים ב-Reactor.java
            // (העברת connections, יצירת ID, וקריאה ל-start/addConnection ב-handleAccept)
            Server.reactor(
                    Runtime.getRuntime().availableProcessors(),
                    port,
                    () -> new StompMessagingProtocolImpl(connections, userController),
                    StompEncoderDecoder::new
            ).serve();
        }
    }
}