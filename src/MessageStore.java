import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves messages so they survive a restart.
 *
 * To stay library-free, the demo appends one line per message to a text file
 * and keeps everything in memory. That's fine for a demo, but a real app would
 * use a database (SQLite, PostgreSQL, ...) so it can search, back up, and
 * delete messages safely.
 *
 * Line format (tab-separated):  id  studentId  senderId  sentAt  text
 */
final class MessageStore {

    private final Path file;
    private final List<Message> messages = new ArrayList<>();
    private long nextId = 1;

    MessageStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 5);
                if (f.length < 5) {
                    continue;   // skip a damaged or blank line
                }
                Message m = new Message(Long.parseLong(f[0]), f[1], f[2], Long.parseLong(f[3]), unescape(f[4]));
                messages.add(m);
                nextId = Math.max(nextId, m.id() + 1);
            }
        }
        System.out.println("Loaded " + messages.size() + " saved messages from " + file.toAbsolutePath());
    }

    /**
     * Saves a new message and returns it (now with an id and timestamp).
     * "synchronized" because many people may send at the same moment, each on
     * their own thread -- only one thread at a time may change the list.
     */
    synchronized Message add(String studentId, String senderId, String text) throws IOException {
        Message m = new Message(nextId++, studentId, senderId, System.currentTimeMillis(), text);
        String line = m.id() + "\t" + m.studentId() + "\t" + m.senderId() + "\t" + m.sentAt() + "\t" + escape(m.text()) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        messages.add(m);
        return m;
    }

    /** All messages in one student's thread, oldest first. */
    synchronized List<Message> forThread(String studentId) {
        List<Message> result = new ArrayList<>();
        for (Message m : messages) {
            if (m.studentId().equals(studentId)) {
                result.add(m);
            }
        }
        return result;
    }

    // Tabs and newlines would break our one-line-per-message format, so we encode them.
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default  -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
