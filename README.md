## How it works

```
 Browser (public/chat.js)                    Server (src/ChatServer.java)
 ------------------------                    ----------------------------
 "Who exists?"     GET  /api/users     -->   fake people list (Directory.java)
 "My chats?"       GET  /api/threads   -->   only the threads you're allowed to see
 "Chat history?"   GET  /api/messages  -->   checks permission, reads MessageStore
 "Send this"       POST /api/messages  -->   checks permission, saves, then...
                                                    |
 "Tell me when     GET  /api/stream    <-----------+  ...pushes it down every open
  something new"   (stays open)                       connection of BOTH people in the thread
```

The interesting idea is **`/api/stream`**. Normally a web request asks a
question, gets an answer, and is done. This one is never "done". The server
keeps the connection open and writes each new message to it the moment it
arrives. That technique is called **Server-Sent Events (SSE)**. The browser
side is one built-in line: `new EventSource("/api/stream?user=...")`.

---

## Read the code in this order

| File | What to look for |
|---|---|
| `src/ChatServer.java` | The URL routes (top of `main`) and what each handler does. Start here. |
| `src/Directory.java` | `canAccessThread()`, the rule for who may see which messages. The most important method in the app. |
| `src/LiveUpdates.java` | How "real time" works: open connections are kept in a map and written to when a message arrives. |
| `src/MessageStore.java` | Saving messages to a file so they survive restarts. `synchronized` keeps two senders from colliding. |
| `public/chat.js` | The browser side: `fetch` for normal requests, `EventSource` for live updates, and `textContent` to display messages safely. |
| `Dockerfile`, `compose.yml` | How the Pi builds and runs it. You shouldn't need to change these. |


## Going further in Java

For your own version, a framework will save you a lot of the plumbing here:
- **Javalin**: lightweight, close to this demo's style, with built-in WebSocket support.
- **Spring Boot**: heavier, but it's what many companies use, and it has login
  (Spring Security) and databases built in.

Either way, the ideas carry over: routes, a permission check on every request,
a store for messages, and a live connection that pushes new ones.
