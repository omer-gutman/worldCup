package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final MessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = true;

    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> encdec, MessagingProtocol<T> protocol) {
        this.sock = sock;
        this.encdec = encdec;
        this.protocol = protocol;
    }

    @Override
    public void run() {
        try (Socket mySock = sock) {
            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            while (!protocol.shouldTerminate() && connected && !Thread.currentThread().isInterrupted()) {
                int read = in.read();
                if (read >= 0) {
                    T nextMessage = encdec.decodeNextByte((byte) read);
                    if (nextMessage != null) {
                        T response = protocol.process(nextMessage);
                        if (response != null) { // תמיכה בפרוטוקולים שמחזירים תשובה ישירה (כמו Echo)
                            send(response); 
                        }
                    }
                } else {
                    connected = false; // הלקוח סגר את החיבור מהצד שלו
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void send(T msg) {
        if (msg != null) {
            try {
                // כתיבה ופלאש לסוקט
                out.write(encdec.encode(msg));
                out.flush();
            } catch (IOException ex1) {
                // ניסיון כתיבה נכשל, ננתק את הלקוח
                try { 
                    close(); 
                } catch (Exception ex2) {}
            }
        }
    }

    @Override
    public void close() throws IOException {
        connected = false;
        sock.close();
    }
}