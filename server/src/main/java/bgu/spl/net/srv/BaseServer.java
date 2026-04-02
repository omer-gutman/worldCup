package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol; //שינוי לפרטוקול STOMP
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<StompMessagingProtocol<T>> protocolFactory; //מעודכן
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private ServerSocket sock;
    
    //מנהל את כל החיבורים
    private final ConnectionsImpl<T> connections;
    //מייצר ID ת'רד סייף
    private final AtomicInteger connectionIdCounter;

    public BaseServer(
            int port,
            Supplier<StompMessagingProtocol<T>> protocolFactory, //מעודכן
            Supplier<MessageEncoderDecoder<T>> encdecFactory) {

        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
        this.sock = null;
        
        this.connections = new ConnectionsImpl<>();
        this.connectionIdCounter = new AtomicInteger(1);
    }

    @Override
    public void serve() {

        try (ServerSocket serverSock = new ServerSocket(port)) {
            System.out.println("Server started");

            this.sock = serverSock; //כדי שנוכל לסגור

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSock = serverSock.accept();
                int connectionId = connectionIdCounter.getAndIncrement();
                StompMessagingProtocol<T> protocol = protocolFactory.get();
                BlockingConnectionHandler<T> handler = new BlockingConnectionHandler<>(
                        clientSock,
                        encdecFactory.get(),
                        protocol);                
                connections.addConnection(connectionId, handler);                
                protocol.start(connectionId, connections);
                execute(handler);
            }
        } catch (IOException ex) {}

        System.out.println("server closed!!!");
    }

    @Override
    public void close() throws IOException {
        if (sock != null)
            sock.close();
    }

    protected abstract void execute(BlockingConnectionHandler<T>  handler);

}