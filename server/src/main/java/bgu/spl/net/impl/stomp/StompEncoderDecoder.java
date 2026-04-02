package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class StompEncoderDecoder implements MessageEncoderDecoder<String> {

    private byte[] bytes = new byte[1 << 10]; // Start with 1k buffer
    private int len = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        // STOMP frames end with the null character '\u0000' 
        if (nextByte == '\u0000') {
            return popString();
        }

        pushByte(nextByte);
        return null; // Not a complete frame yet
    }

    @Override
    public byte[] encode(String message) {
        // The protocol already appends the '\u0000' character to the end of the string
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private void pushByte(byte nextByte) {
        if (len >= bytes.length) {
            bytes = Arrays.copyOf(bytes, len * 2);
        }
        bytes[len++] = nextByte;
    }

    private String popString() {
        String result = new String(bytes, 0, len, StandardCharsets.UTF_8);
        len = 0;
        return result;
    }
}