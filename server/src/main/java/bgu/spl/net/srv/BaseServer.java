package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.api.StompMessagingProtocol;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private ServerSocket sock;
    
    // ניהול ID בצורה אטומית 
    private final AtomicInteger connectionIdCounter = new AtomicInteger(0);
    private final ConnectionsImpl<T> connections = new ConnectionsImpl<>();

    public BaseServer(int port, Supplier<MessagingProtocol<T>> protocolFactory, Supplier<MessageEncoderDecoder<T>> encdecFactory) {
        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
        this.sock = null;
    }

    @Override
    public void serve() {
        try (ServerSocket serverSock = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);
            this.sock = serverSock; //השרת מממש ממשק Closeable לכן צריך לשמור את הסוקט כדי שנוכל לסגור בהמשך

            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSock = serverSock.accept();

                // יצירת רכיבים עבור הלקוח החדש
                MessagingProtocol<T> protocol = protocolFactory.get();
                BlockingConnectionHandler<T> handler = new BlockingConnectionHandler<>(
                        clientSock,
                        encdecFactory.get(),
                        protocol);
                int id = connectionIdCounter.getAndIncrement();
                connections.addConnection(id, handler);
                
                // אם נעבוד עם STOMP צריך להתייחס לזה ולהפעיל start
                if (protocol instanceof StompMessagingProtocol) {
                    ((StompMessagingProtocol<T>) protocol).start(id, connections);
                }

                execute(handler); // הרצת ה-handler בטרד נפרד
            }
        } catch (IOException ex) {
            // גם אם נזהה כאן חריגה, לא נרצה להקריס הכל
        } finally {
            try {
                close();
            } catch (IOException ex) {}
        }
        System.out.println("Server closed");
    }

    @Override // הסיבה היחידה לשמירת sock
    public void close() throws IOException {
        if (sock != null)
            sock.close();
    }

    protected abstract void execute(BlockingConnectionHandler<T> handler);
}