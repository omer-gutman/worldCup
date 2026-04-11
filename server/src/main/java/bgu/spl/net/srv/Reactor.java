package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol; 
import bgu.spl.net.api.StompMessagingProtocol; 
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Reactor<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory; 
    private final Supplier<MessageEncoderDecoder<T>> readerFactory;
    private final ActorThreadPool pool;
    private Selector selector;

    private Thread selectorThread;
    private final ConcurrentLinkedQueue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();
    
    //מונה אטומי בשביל ID ייחודי, רשימת חיבורים.
    private final ConnectionsImpl<T> connections;
    private final AtomicInteger connectionIdCounter;

    public Reactor(
            int numThreads,
            int port,
            Supplier<MessagingProtocol<T>> protocolFactory, //מעודכן
            Supplier<MessageEncoderDecoder<T>> readerFactory) {

        this.pool = new ActorThreadPool(numThreads);
        this.port = port;
        this.protocolFactory = protocolFactory;
        this.readerFactory = readerFactory;
        
        this.connections = new ConnectionsImpl<>();
        this.connectionIdCounter = new AtomicInteger(1);
    }

    @Override
    public void serve() {
        selectorThread = Thread.currentThread();
        try (Selector selector = Selector.open();
                ServerSocketChannel serverSock = ServerSocketChannel.open()) {

            this.selector = selector; //כדי שנוכל לסגור

            serverSock.bind(new InetSocketAddress(port));
            serverSock.configureBlocking(false);
            serverSock.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server started");

            while (!Thread.currentThread().isInterrupted()) {

                selector.select();
                runSelectionThreadTasks();

                for (SelectionKey key : selector.selectedKeys()) {

                    if (!key.isValid()) {
                        continue;
                    } else if (key.isAcceptable()) {
                        handleAccept(serverSock, selector);
                    } else {
                        handleReadWrite(key);
                    }
                }

                selector.selectedKeys().clear(); //לנקות מפתחות קיימים כדי שנוכל לקבל אירועים חדשים

            }

        } catch (ClosedSelectorException ex) {
            //אם ביקשו שנסגור את השרת - לא לעשות כלום
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        System.out.println("server closed!!!");
        pool.shutdown();
    }

    /*package*/ void updateInterestedOps(SocketChannel chan, int ops) {
        final SelectionKey key = chan.keyFor(selector);
        
        // הגנה ראשונה: אם המפתח כבר לא קיים או בוטל, אין מה לעדכן
        if (key == null || !key.isValid()) {
            return;
        }

        if (Thread.currentThread() == selectorThread) {
            key.interestOps(ops);
        } else {
            selectorTasks.add(() -> {
                // הגנה שנייה: בדיקה חוזרת בתוך התור, כי המפתח יכול היה להתבטל
                // בזמן שהמשימה חיכתה בתור לביצוע
                if (key.isValid()) {
                    key.interestOps(ops);
                }
            });
            selector.wakeup();
        }
    }

    private void handleAccept(ServerSocketChannel serverChan, Selector selector) throws IOException {
        SocketChannel clientChan = serverChan.accept();
        clientChan.configureBlocking(false);

        int connectionId = connectionIdCounter.getAndIncrement();
        MessagingProtocol<T> protocol = protocolFactory.get(); 
        
        //יצירת האנדלר עם הפרוטוקול הגנרי
        final NonBlockingConnectionHandler<T> handler = new NonBlockingConnectionHandler<>(
                readerFactory.get(),
                protocol,
                clientChan,
                this);
                
        connections.addConnection(connectionId, handler);

        // בדיקה אם הפרוטוקול הוא STOMP ואם כן, start
        pool.submit(handler, () -> {
            if (protocol instanceof StompMessagingProtocol) {
                ((StompMessagingProtocol<T>) protocol).start(connectionId, connections);
            }
            
            // פותחים ערוץ לקריאה רק אחרי start
            updateInterestedOps(clientChan, SelectionKey.OP_READ);
        });
    
    // רישום ראשוני כדי למנוע קריאה לפני start
    clientChan.register(selector, 0, handler); 
}

    private void handleReadWrite(SelectionKey key) {
        @SuppressWarnings("unchecked")
        NonBlockingConnectionHandler<T> handler = (NonBlockingConnectionHandler<T>) key.attachment();

        if (key.isReadable()) {
            Runnable task = handler.continueRead();
            if (task != null) {
                pool.submit(handler, task);
            }
        }

        if (key.isValid() && key.isWritable()) {
            handler.continueWrite();
        }
    }

    private void runSelectionThreadTasks() {
        while (!selectorTasks.isEmpty()) {
            selectorTasks.remove().run();
        }
    }

    @Override
    public void close() throws IOException {
        selector.close();
    }

}