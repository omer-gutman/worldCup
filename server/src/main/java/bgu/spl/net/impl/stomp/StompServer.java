package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;
import bgu.spl.net.srv.Reactor;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.api.MessageEncoderDecoder;
import java.io.IOException;
import java.util.function.Supplier;

public class StompServer {

    public static void main(String[] args) {
        // בדיקת ארגומנטים לפי הדרישות: <port> <tpc/reactor>
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <tpc/reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        //משתמשים בממשק המקורי אבל במימוש החדש
        Supplier<MessagingProtocol<String>> protocolFactory = () -> new StompMessagingProtocolImpl();
        Supplier<MessageEncoderDecoder<String>> encdecFactory = () -> new StompEncoderDecoder();

        if (serverType.equals("tpc")) {
            // מימוש TPC
            try (Server<String> server = new BaseServer<String>(port, protocolFactory, encdecFactory) {
                @Override
                protected void execute(BlockingConnectionHandler<String> handler) {
                    new Thread(handler).start();
                }
            }) {
                server.serve();
            } catch (IOException e) {
                e.printStackTrace();
            }

        } else if (serverType.equals("reactor")) {
            // מימוש Reactor עם מספר טרדים לפי מספר הליבות במעבד 
            try (Server<String> server = new Reactor<String>(
                    Runtime.getRuntime().availableProcessors(),
                    port,
                    protocolFactory,
                    encdecFactory)) {
                server.serve();
            } catch (IOException e) {
                e.printStackTrace();
            }
            
        } else {
            System.out.println("Invalid server type. Choose 'tpc' or 'reactor'.");
        }
    }
}