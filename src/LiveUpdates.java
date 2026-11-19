import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The "real-time" part: pushes new messages to browsers the instant they're sent.
 *
 * HOW IT WORKS (Server-Sent Events, or SSE):
 *   1. When someone opens the chat, their browser makes ONE request to /api/stream
 *      and the server simply never finishes answering it. The connection stays open.
 *   2. Whenever a message is sent, the server writes it down every open connection
 *      that belongs to the thread's participants. The browser gets it immediately.
 *   3. Every 25 seconds we send a tiny "ping" so idle connections aren't cut off,
 *      and so we notice browsers that have gone away (the write fails).
 *
 * WebSockets are the other common way to do this. They're two-way, where SSE is
 * server-to-browser only, but chat works fine with SSE plus a normal POST for
 * sending. Frameworks like Javalin or Spring Boot make WebSockets easy.
 */
final class LiveUpdates {

    /** userId -> that user's open connections (one per open browser tab). */
    private final Map<String, Set<Listener>> listeners = new ConcurrentHashMap<>();

    LiveUpdates() {
        ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleAtFixedRate(this::pingEveryone, 25, 25, TimeUnit.SECONDS);
    }

    /**
     * Holds a browser's connection open until it disconnects.
     * This blocks the calling thread on purpose -- we run each request on a
     * Java 21 "virtual thread", which is cheap enough to have thousands waiting.
     */
    void listen(String userId, OutputStream out) throws InterruptedException {
        Listener listener = new Listener(out);
        listeners.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(listener);
        System.out.println("Live connection opened for " + userId);
        listener.send(": connected\n\n");   // lines starting with ':' are comments in SSE
        try {
            listener.closed.await();        // wait here until a write fails (browser left)
        } finally {
            listeners.get(userId).remove(listener);
            System.out.println("Live connection closed for " + userId);
        }
    }

    /** Sends a new message to every open connection of the given users. */
    void publish(Message message, List<String> userIds) {
        String event = "data: " + message.toJson() + "\n\n";   // the SSE wire format
        for (String userId : userIds) {
            for (Listener listener : listeners.getOrDefault(userId, Set.of())) {
                listener.send(event);
            }
        }
    }

    private void pingEveryone() {
        for (Set<Listener> set : listeners.values()) {
            for (Listener listener : set) {
                listener.send(": ping\n\n");
            }
        }
    }

    /** One open browser connection. */
    private static final class Listener {
        private final OutputStream out;
        private final CountDownLatch closed = new CountDownLatch(1);

        Listener(OutputStream out) {
            this.out = out;
        }

        synchronized void send(String text) {
            if (closed.getCount() == 0) {
                return;
            }
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();                 // push it out now instead of buffering
            } catch (IOException e) {
                closed.countDown();          // browser is gone; release listen()
            }
        }
    }
}
