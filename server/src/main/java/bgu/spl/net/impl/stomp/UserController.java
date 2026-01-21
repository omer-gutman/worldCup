package bgu.spl.net.impl.stomp;

import java.util.concurrent.ConcurrentHashMap;

public class UserController {
    // שם משתמש -> סיסמה
    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();
    // שם משתמש -> האם מחובר כרגע
    private final ConcurrentHashMap<String, Boolean> activeLogins = new ConcurrentHashMap<>();

    public synchronized boolean isUserRegistered(String username) {
        return users.containsKey(username);
    }

    public synchronized void register(String username, String password) {
        users.put(username, password);
    }

    public synchronized boolean isValidLogin(String username, String password) {
        return password.equals(users.get(username));
    }

    public synchronized boolean isLoggedIn(String username) {
        return activeLogins.getOrDefault(username, false);
    }

    public synchronized void login(String username) {
        activeLogins.put(username, true);
    }

    public synchronized void logout(String username) {
        activeLogins.put(username, false);
    }
}