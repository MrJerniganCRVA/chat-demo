/**
 * One chat message. studentId says which thread it belongs to.
 * sentAt is milliseconds since 1970 (what System.currentTimeMillis() returns),
 * which JavaScript's Date understands directly.
 */
record Message(long id, String studentId, String senderId, long sentAt, String text) {

    String toJson() {
        User sender = Directory.find(senderId);
        return "{"
            + "\"id\":" + id + ","
            + "\"studentId\":" + Json.str(studentId) + ","
            + "\"senderId\":" + Json.str(senderId) + ","
            + "\"senderName\":" + Json.str(sender == null ? "Unknown" : sender.name()) + ","
            + "\"sentAt\":" + sentAt + ","
            + "\"text\":" + Json.str(text)
            + "}";
    }
}
