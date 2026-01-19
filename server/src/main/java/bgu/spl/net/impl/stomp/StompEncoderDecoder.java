package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class StompEncoderDecoder implements MessageEncoderDecoder<StompFrame> {

    private byte[] bytes = new byte[1024]; // להתחיל עם מערך של 1KB
    private int len = 0; // עוקב אחרי האורך של המסגרת הנוכחית

    @Override
    public StompFrame decodeNextByte(byte nextByte) {
        // בודק אם הגענו לסוף המסגרת (אם הבייט הוא null character)
        if (nextByte == '\u0000') {
            return popFrame(); // מחזיר את המסגרת המלאה
        }
        // אם לא הגענו לסוף, מוסיף את הבייט למערך
        pushByte(nextByte);
        return null; // לעדכן את השרת שלא סיימנו עדיין
    }

    @Override
    public byte[] encode(StompFrame message) {
        // להפוך את המסגרת למחרוזת ולהוסיף את תו הסיום
        //  משתמש בקידוד שיצרנוUTF-8 להמרה לבייטים
        return (message.toString() + "\u0000").getBytes(StandardCharsets.UTF_8);
    }

    private void pushByte(byte nextByte) {
        // אם המסגרת מלאה , להגדיל את המערך (משתמש בדינמיות של מערכים)
        if (len >= bytes.length) {
            bytes = Arrays.copyOf(bytes, len * 2);
        }
        bytes[len++] = nextByte;
    }

    private StompFrame popFrame() {
        // 1. ממיר את הבתים שאספנו למחרוזת 
        // מקבל כארגומנטים את המערך התחלה סוף וקידוד
        String rawMessage = new String(bytes, 0, len, StandardCharsets.UTF_8);
        len = 0; // נאתחל את האורך למסגרת הבאה
        
        // 2. נחזיר את המסגרת המפורסת
        return parseMessage(rawMessage);
    }

    private StompFrame parseMessage(String msg) {
        // פיצול המחרוזת לשורות לפי תו השורה החדשה
        String[] lines = msg.split("\n");
        if (lines.length == 0) return null;

        // השורה הראשונה היא הפקודה
        String command = lines[0].trim();
        StompFrame frame = new StompFrame(command);

        // לפרס את הכותרות
        int i = 1;
        // נמשיך לקרוא כותרות עד שנגיע לשורה ריקה
        while (i < lines.length && !lines[i].isEmpty()) {
            String[] headerParts = lines[i].split(":");
            if (headerParts.length == 2) {
                frame.addHeader(headerParts[0], headerParts[1]);
            }
            i++;
        }

        // הגוף הוא כל מה שנשאר אחרי השורה הריקה
        StringBuilder body = new StringBuilder();
        for (i = i + 1; i < lines.length; i++) {
            body.append(lines[i]).append("\n");
        }
        
        if (body.length() > 0) {
            frame.setBody(body.toString().trim()); 
        }

        return frame;
    }
}