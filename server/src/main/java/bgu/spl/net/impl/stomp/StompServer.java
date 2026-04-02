package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;
import bgu.spl.net.srv.Reactor;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.api.MessageEncoderDecoder;
import java.io.IOException;
import java.util.function.Supplier;

public class StompServer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <tpc/reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        // Define factories once to keep the code DRY and clear
        Supplier<StompMessagingProtocol<String>> protocolFactory = () -> new StompMessagingProtocolImpl();
        Supplier<MessageEncoderDecoder<String>> encdecFactory = () -> new StompEncoderDecoder();

        if (serverType.equals("tpc")) {
            // Using try-with-resources clears the "Resource Leak" warning
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