# Case Manager Chat: Reference Demo

A small, working proof of concept: students and case managers send each other
private messages that show up **instantly** on the other person's screen.

It's written in **plain Java 21 with no frameworks or libraries**, so every part
of the web server is code you can read. It's a reference to learn from. Your app
should be your own design.

> **Demo only.** Fake names, no passwords, school network only. Never put real
> student information into this demo.

---

## Try it (2-minute demo)

1. Open the site in two browser windows side by side.
2. In window 1, pick **Alex P.** (a student). In window 2, pick **Ms. Rivera** (Alex's case manager).
3. Send a message from either window and watch it appear in the other one, with no refresh.
4. In window 2, notice that Ms. Rivera sees *both* of her students. Messages from a student
   whose thread isn't open show a red unread count.
5. Switch window 1 to **Sam T.** You won't see Alex's conversation. Sam has a different case manager.

---

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

---

## Run it on your own computer

You need Java 21 or newer (`java -version` shows your version).

```
javac -d out src/*.java
java -cp out ChatServer
```

Open http://localhost:8080. Messages are saved in `data/messages.txt`. Delete
that file to start fresh.

## Run it on a school Pi

Push this folder to a GitHub repo and point the Pi's setup script at it. The Pi
sees `compose.yml`, builds the app with Docker, and redeploys on every push. The
first build takes a few minutes. After that, pushes deploy in about a minute.

---

## The demo cuts corners on purpose. A real version needs these

1. **Real login.** Right now the browser *says* who it is (`?user=s1`) and the
   server believes it, so anyone could pretend to be anyone. A real app signs
   people in (for example with their school Google account), and the **server**
   tracks who they are with a session. This is the #1 change.
2. **Privacy approval first.** Messages between students and SPED case managers
   are likely education records (FERPA), and IEP details are highly sensitive.
   Before you build, talk with the school admin or SPED coordinator: What may be
   sent in a message? Who can read the logs? How long are messages kept? Is there
   already an approved tool?
3. **A real database** (SQLite, PostgreSQL) instead of a text file, so messages
   can be searched, backed up, and deleted properly.
4. **HTTPS**, so messages can't be read in transit.
5. **Things users will expect:** notifications, read receipts, "case manager is
   away" status, and what happens when a student's case manager changes.

## Going further in Java

For your own version, a framework will save you a lot of the plumbing here:
- **Javalin**: lightweight, close to this demo's style, with built-in WebSocket support.
- **Spring Boot**: heavier, but it's what many companies use, and it has login
  (Spring Security) and databases built in.

Either way, the ideas carry over: routes, a permission check on every request,
a store for messages, and a live connection that pushes new ones.
