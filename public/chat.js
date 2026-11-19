// Case Manager Chat -- browser side.
//
// The browser's three jobs:
//   1. Ask the server for data (fetch):   who exists, my conversations, message history
//   2. Send messages (fetch with POST)
//   3. Keep one connection open (EventSource) so new messages show up instantly

let me = null;              // the user we're pretending to be
let currentStudent = null;  // which thread is open (threads are identified by student id)
let stream = null;          // the live EventSource connection
let shownIds = new Set();   // message ids already on screen, so nothing shows twice
const unread = {};          // studentId -> count of unread messages

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- startup

async function init() {
  const users = await getJson("/api/users");
  const list = $("user-list");
  for (const user of users) {
    const button = document.createElement("button");
    button.textContent = user.name;
    const role = document.createElement("small");
    role.textContent = user.role === "student" ? "Student" : "Case manager";
    button.appendChild(role);
    button.addEventListener("click", () => login(user));
    list.appendChild(button);
  }

  // Remember who this tab was, so a refresh doesn't log you out.
  const savedId = safeStorage("get", "demoUserId");
  const saved = users.find((u) => u.id === savedId);
  if (saved) login(saved);
}

function login(user) {
  me = user;
  safeStorage("set", "demoUserId", user.id);
  $("whoami").textContent = "Signed in as " + user.name;
  $("whoami").hidden = false;
  $("logout").hidden = false;
  $("login-view").hidden = true;
  $("chat-view").hidden = false;
  openLiveConnection();
  loadThreads();
}

$("logout").addEventListener("click", () => {
  safeStorage("remove", "demoUserId");
  if (stream) stream.close();
  location.reload();
});

// -------------------------------------------------------------- threads

async function loadThreads() {
  const threads = await getJson(`/api/threads?user=${me.id}`);
  const list = $("thread-list");
  list.innerHTML = "";
  for (const thread of threads) {
    const li = document.createElement("li");
    const button = document.createElement("button");
    button.dataset.student = thread.studentId;
    button.dataset.title = thread.title;
    button.addEventListener("click", () => openThread(thread.studentId, thread.title));
    li.appendChild(button);
    list.appendChild(li);
  }
  renderThreadButtons();
  // A student only has one conversation, so open it automatically.
  if (threads.length === 1) openThread(threads[0].studentId, threads[0].title);
}

function renderThreadButtons() {
  for (const button of document.querySelectorAll("#thread-list button")) {
    const id = button.dataset.student;
    button.textContent = button.dataset.title;
    button.classList.toggle("active", id === currentStudent);
    if (unread[id]) {
      const dot = document.createElement("span");
      dot.className = "unread-dot";
      dot.textContent = unread[id];
      button.appendChild(dot);
    }
  }
}

async function openThread(studentId, title) {
  currentStudent = studentId;
  unread[studentId] = 0;
  $("thread-title").textContent = title;
  $("send-form").hidden = false;
  renderThreadButtons();

  const messages = await getJson(`/api/messages?user=${me.id}&student=${studentId}`);
  $("messages").innerHTML = "";
  shownIds = new Set();
  messages.forEach(showMessage);
  $("message-input").focus();
}

// ------------------------------------------------------------- messages

function showMessage(message) {
  if (shownIds.has(message.id)) return;
  shownIds.add(message.id);

  const li = document.createElement("li");
  if (message.senderId === me.id) li.classList.add("mine");

  const meta = document.createElement("span");
  meta.className = "meta";
  const time = new Date(message.sentAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  meta.textContent = `${message.senderName} · ${time}`;

  // textContent (never innerHTML) for anything a user typed. Otherwise someone
  // could send "<script>...</script>" and it would run in the other person's browser.
  const text = document.createElement("span");
  text.className = "text";
  text.textContent = message.text;

  li.append(meta, text);
  $("messages").appendChild(li);
  li.scrollIntoView({ block: "end" });
}

$("send-form").addEventListener("submit", async (event) => {
  event.preventDefault();             // don't reload the page
  const input = $("message-input");
  const text = input.value.trim();
  if (!text || !currentStudent) return;

  const response = await fetch("/api/messages", {
    method: "POST",
    body: new URLSearchParams({ user: me.id, student: currentStudent, text }),
  });
  if (response.ok) {
    input.value = "";
    // We don't add the message to the screen here -- the server pushes it back
    // to us over the live connection, the same way the other person gets it.
  } else {
    const error = await response.json();
    alert(error.error || "Message failed to send.");
  }
});

// ------------------------------------------------------ live connection

function openLiveConnection() {
  // EventSource is built into browsers. It keeps a connection open to the
  // server and automatically reconnects if it drops.
  stream = new EventSource(`/api/stream?user=${me.id}`);

  stream.onopen = () => {
    $("connection").textContent = "Live: new messages appear automatically";
    // If we were disconnected, we may have missed messages -- reload the thread.
    if (currentStudent) openThread(currentStudent, $("thread-title").textContent);
  };

  stream.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.studentId === currentStudent) {
      showMessage(message);
    } else {
      unread[message.studentId] = (unread[message.studentId] || 0) + 1;
      renderThreadButtons();
    }
  };

  stream.onerror = () => {
    $("connection").textContent = "Reconnecting...";
  };
}

// --------------------------------------------------------------- helpers

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} failed: ${response.status}`);
  return response.json();
}

// sessionStorage can be unavailable (privacy modes, some embedded browsers).
function safeStorage(action, key, value) {
  try {
    if (action === "get") return sessionStorage.getItem(key);
    if (action === "set") sessionStorage.setItem(key, value);
    if (action === "remove") sessionStorage.removeItem(key);
  } catch (e) {
    return null;
  }
}

init();
