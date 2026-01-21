package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<StompFrame> {

    private int connectionId;
    private Connections<StompFrame> connections;
    private boolean shouldTerminate = false;
    private UserController userController;
    private String currentUser = null; 
    
    private Map<String, String> subscriptionIdToChannel = new HashMap<>();

    public StompMessagingProtocolImpl(Connections<StompFrame> connections, UserController userController) {
        this.connections = connections;
        this.userController = userController;
    }

    @Override
    public void start(int connectionId, Connections<StompFrame> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public StompFrame process(StompFrame message) {
        String command = message.getCommand();

        if (currentUser == null && !command.equals("CONNECT")) {
            sendErrorAndClose("User not logged in", "You must log in first.", message);
            return null;
        }

        switch (command) {
            case "CONNECT":
                handleConnect(message);
                break;
            case "SUBSCRIBE":
                handleSubscribe(message);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(message);
                break;
            case "SEND":
                handleSend(message);
                break;
            case "DISCONNECT":
                handleDisconnect(message);
                break;
            default:
                sendErrorAndClose("Unknown Command", "The command " + command + " is not recognized.", message);
                break;
        }
        
        // אנחנו מחזירים null כי התשובות נשלחות ישירות דרך ה-connections ולא דרך מנגנון החזרה של ה-Handler
        return null;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // --- Private Handler Methods ---

    private void handleConnect(StompFrame message) {
        String login = message.getHeaders().get("login");
        String passcode = message.getHeaders().get("passcode");

        if (login == null || passcode == null) {
            sendErrorAndClose("Malformed Frame", "Login and passcode headers are required", message);
            return;
        }

        if (currentUser != null) {
            sendErrorAndClose("User already logged in", "Client is already logged in as " + currentUser, message);
            return;
        }

        synchronized (userController) {
            if (userController.isLoggedIn(login)) {
                sendErrorAndClose("User already logged in", "User " + login + " is already active on another client.", message);
                return;
            }

            if (!userController.isUserRegistered(login)) {
                userController.register(login, passcode);
            } else {
                if (!userController.isValidLogin(login, passcode)) {
                    sendErrorAndClose("Wrong password", "Password does not match.", message);
                    return;
                }
            }
            
            userController.login(login);
        }

        this.currentUser = login;

        StompFrame connected = new StompFrame("CONNECTED");
        connected.addHeader("version", "1.2");
        connections.send(connectionId, connected);
    }

    private void handleSubscribe(StompFrame message) {
        String destination = message.getHeaders().get("destination");
        String id = message.getHeaders().get("id");

        if (destination == null || id == null) {
            sendErrorAndClose("Malformed Frame", "SUBSCRIBE requires 'destination' and 'id' headers.", message);
            return;
        }

        subscriptionIdToChannel.put(id, destination);
        connections.subscribe(destination, connectionId, id);

        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleUnsubscribe(StompFrame message) {
        String id = message.getHeaders().get("id");
        if (id == null) {
            sendErrorAndClose("Malformed Frame", "UNSUBSCRIBE requires 'id' header.", message);
            return;
        }

        String destination = subscriptionIdToChannel.remove(id);
        if (destination != null) {
            connections.unsubscribe(destination, connectionId);
        }

        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleSend(StompFrame message) {
        String destination = message.getHeaders().get("destination");
        if (destination == null) {
            sendErrorAndClose("Malformed Frame", "SEND requires 'destination' header.", message);
            return;
        }

        if (!subscriptionIdToChannel.containsValue(destination)) {
            sendErrorAndClose("Not Subscribed", "You cannot send messages to a channel you are not subscribed to.", message);
            return;
        }

        StompFrame msgToSend = new StompFrame("MESSAGE");
        msgToSend.addHeader("destination", destination);
        msgToSend.addHeader("message-id", "msg-" + System.currentTimeMillis()); 
        msgToSend.setBody(message.getBody());

        connections.send(destination, msgToSend);

        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
    }

    private void handleDisconnect(StompFrame message) {
        if (message.getHeaders().containsKey("receipt")) {
            sendReceipt(message.getHeaders().get("receipt"));
        }
        
        if (currentUser != null) {
            userController.logout(currentUser);
        }
        
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private void sendReceipt(String receiptId) {
        StompFrame receipt = new StompFrame("RECEIPT");
        receipt.addHeader("receipt-id", receiptId);
        connections.send(connectionId, receipt);
    }

    private void sendErrorAndClose(String messageHeader, String bodyDetail, StompFrame causingMessage) {
        StompFrame error = new StompFrame("ERROR");
        error.addHeader("message", messageHeader);
        if (causingMessage != null && causingMessage.getHeaders().containsKey("receipt")) {
            error.addHeader("receipt-id", causingMessage.getHeaders().get("receipt"));
        }
        error.setBody("The connection will be closed.\n" + bodyDetail);
        connections.send(connectionId, error);
        
        if (currentUser != null) {
            userController.logout(currentUser);
        }
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
}