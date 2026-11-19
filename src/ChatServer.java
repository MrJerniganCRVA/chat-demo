import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Case-manager chat -- reference demo.
 *
 * The whole server in one picture:
 *
 *   Browser                         ChatServer (this program)
 *   -------                         -------------------------
 *   GET  /                    -->   send the web page (files in /public)
 *   GET  /api/users           -->   list of people for the demo "login"
 *   GET  /api/threads         -->   which conversations this user may see
 *   GET  /api/messages        -->   history of one conversation
 *   POST /api/messages        -->   save a new message, then push it out live
 *   GET  /api/stream          -->   stays open; new messages arrive here
 *
 * Uses only the JDK (Java 21). No frameworks, no libraries.
 *
 * Run locally:   javac -d out src/*.java  &&  java -cp out ChatServer
 * Then open:     http://localhost:8080
 */
public class ChatServer {

    static final int PORT = Integer.parseInt(env("PORT", "8080"));
    static final Path PUBLIC_DIR = Path.of(env("PUBLIC_DIR", "public")).toAbsolutePath().normalize();
    static final Path DATA_FILE = Path.of(env("DATA_DIR", "data"), "messages.txt");
    static final int MAX_MESSAGE_LENGTH = 1000;

    static MessageStore store;
    static final LiveUpdates live = new LiveUpdates();

    public static void main(String[] args) throws IOException {
        store = new MessageStore(DATA_FILE);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        // Each request runs on its own virtual thread (Java 21), so open live
        // connections can wait without tying up the rest of the server.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/api/users",    safe(ChatServer::handleUsers));
        server.createContext("/api/threads",  safe(ChatServer::handleThreads));
        server.createContext("/api/messages", safe(ChatServer::handleMessages));
        server.createContext("/api/stream",   safe(ChatServer::handleStream));
        server.createContext("/",             safe(ChatServer::handleStaticFile));

        server.start();
        System.out.println("Chat demo running on http://localhost:" + PORT);
    }

    // ------------------------------------------------------------------ API

    /** GET /api/users -- everyone in the demo, for the pick-who-you-are screen. */
    static void handleUsers(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        List<String> json = new ArrayList<>();
        for (User u : Directory.ALL) {
            json.add(u.toJson());
        }
        sendJson(ex, 200, Json.array(json));
    }

    /** GET /api/threads?user=ID -- the conversations this user is allowed to see. */
    static void handleThreads(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        User me = currentUser(ex, query(ex));
        if (me == null) return;

        List<String> json = new ArrayList<>();
        for (User student : Directory.threadsFor(me)) {
            String title = me.isStudent()
                ? "Chat with " + Directory.find(student.caseManagerId()).name()
                : student.name();
            json.add("{\"studentId\":" + Json.str(student.id()) + ",\"title\":" + Json.str(title) + "}");
        }
        sendJson(ex, 200, Json.array(json));
    }

    /**
     * GET  /api/messages?user=ID&student=SID         -- read a thread's history
     * POST /api/messages   (form: user, student, text) -- send a message
     */
    static void handleMessages(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();

        if (method.equals("GET")) {
            Map<String, String> params = query(ex);
            User me = currentUser(ex, params);
            if (me == null) return;
            String studentId = params.get("student");
            if (!Directory.canAccessThread(me, studentId)) {
                sendJson(ex, 403, "{\"error\":\"You can't view that conversation.\"}");
                return;
            }
            List<String> json = new ArrayList<>();
            for (Message m : store.forThread(studentId)) {
                json.add(m.toJson());
            }
            sendJson(ex, 200, Json.array(json));

        } else if (method.equals("POST")) {
            Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            User me = currentUser(ex, form);
            if (me == null) return;
            String studentId = form.get("student");
            String text = form.getOrDefault("text", "").strip();

            if (!Directory.canAccessThread(me, studentId)) {
                sendJson(ex, 403, "{\"error\":\"You can't post in that conversation.\"}");
                return;
            }
            if (text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH) {
                sendJson(ex, 400, "{\"error\":\"Messages must be 1-" + MAX_MESSAGE_LENGTH + " characters.\"}");
                return;
            }

            Message saved = store.add(studentId, me.id(), text);         // 1. save it
            live.publish(saved, Directory.participants(studentId));       // 2. push it to both people
            sendJson(ex, 201, saved.toJson());                            // 3. tell the sender it worked

        } else {
            sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    /** GET /api/stream?user=ID -- a connection that stays open and receives new messages. */
    static void handleStream(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        User me = currentUser(ex, query(ex));
        if (me == null) return;

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");   // tells nginx not to hold messages back
        ex.sendResponseHeaders(200, 0);                            // 0 = "length unknown, keep streaming"

        OutputStream out = ex.getResponseBody();
        try {
            live.listen(me.id(), out);                             // blocks until the browser leaves
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                out.close();
            } catch (IOException expected) {
                // The browser already left, so closing fails. That's normal here.
            }
        }
    }

    // ---------------------------------------------------------- static files

    /** Serves index.html, chat.js, style.css from the public folder. */
    static void handleStaticFile(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        Path file = PUBLIC_DIR.resolve(path.substring(1)).normalize();

        // Security: refuse paths like /../../etc/passwd that escape the public folder.
        if (!file.startsWith(PUBLIC_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        send(ex, 200, contentType(file), Files.readAllBytes(file));
    }

    static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js"))   return "text/javascript; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".png"))  return "image/png";
        return "application/octet-stream";
    }

    // --------------------------------------------------------------- helpers

    /**
     * Works out who is making the request.
     *
     * !!! DEMO SHORTCUT -- THE #1 THING A REAL VERSION MUST CHANGE !!!
     * Here the browser simply says "I am user s1" and we believe it, so anyone
     * could pretend to be anyone. A real app has people log in (for example with
     * their school Google account) and the SERVER remembers who they are using a
     * session cookie. The browser never gets to choose its own identity.
     */
    static User currentUser(HttpExchange ex, Map<String, String> params) throws IOException {
        User user = Directory.find(params.get("user"));
        if (user == null) {
            sendJson(ex, 401, "{\"error\":\"Unknown user\"}");
        }
        return user;
    }

    static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (ex.getRequestMethod().equals(method)) {
            return true;
        }
        sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
        return false;
    }

    static Map<String, String> query(HttpExchange ex) {
        return parseForm(ex.getRequestURI().getRawQuery());
    }

    /** Parses "a=1&b=hello%20there" into a map. Used for both URLs and form posts. */
    static Map<String, String> parseForm(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json);
    }

    static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        send(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Wraps a handler so a bug in one request logs an error instead of silently hanging. */
    static HttpHandler safe(HttpHandler handler) {
        return ex -> {
            try {
                handler.handle(ex);
            } catch (Exception e) {
                System.err.println("Error handling " + ex.getRequestURI() + ": " + e);
                try {
                    sendJson(ex, 500, "{\"error\":\"Server error\"}");
                } catch (IOException ignored) {
                    // response was already started; nothing more we can do
                }
            } finally {
                ex.close();
            }
        };
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
