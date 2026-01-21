package bgu.spl.net.api;

import bgu.spl.net.srv.Connections;

public interface StompMessagingProtocol<T> extends MessagingProtocol<T> {
	/**
	 * Used to initiate the current client protocol with it's personal connection ID and the connections implementation
	**/
    void start(int connectionId, Connections<T> connections);
    
    // שינוי מ-void ל-T כדי למנוע התנגשות עם MessagingProtocol
    T process(T message);
	
	/**
     * @return true if the connection should be terminated
     */
    boolean shouldTerminate();
}