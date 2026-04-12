package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class StompEncoderDecoder implements MessageEncoderDecoder<String> {

    private byte[] bytes = new byte[1 << 10]; // Start with 1k buffer
    private int len = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        // פריימים נגמרים בנאל בייט, אם מזהים אותו יש פריים שלם
        if (nextByte == '\u0000') {
            return popString();
        }

        pushByte(nextByte);
        return null; // עוד לא פריים שלם
    }

    @Override
    public byte[] encode(String message) {
        // בStompFrame אנחנו כבר מוסיפים את הנאל בייט אז כששולחים כאן לא צריך להוסיף שוב
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