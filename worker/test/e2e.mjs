// End-to-end check of a running worker (`npm run dev`, or a deployed one):
//   node test/e2e.mjs [ws://localhost:8787/ws] [--idle]
// --idle also waits long enough for the Durable Object to be evicted and checks that rooms survive.
import assert from "node:assert/strict";

const url = process.argv.find((a) => a.startsWith("ws")) ?? "ws://localhost:8787/ws";
const testEviction = process.argv.includes("--idle");
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Client {
  constructor(name) {
    this.name = name;
    this.queue = [];
    this.waiters = [];
    this.closed = false;
  }

  async connect() {
    this.socket = new WebSocket(url);
    this.socket.onmessage = (event) => {
      const message = JSON.parse(event.data);
      const waiter = this.waiters.shift();
      if (waiter) waiter(message);
      else this.queue.push(message);
    };
    this.socket.onclose = () => {
      this.closed = true;
    };
    await new Promise((resolve, reject) => {
      this.socket.onopen = resolve;
      this.socket.onerror = () => reject(new Error(`${this.name}: could not connect to ${url}`));
    });
    return this;
  }

  send(message) {
    this.socket.send(JSON.stringify(message));
  }

  next(timeout = 5000) {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.name}: no message within ${timeout} ms`)), timeout);
      this.waiters.push((message) => {
        clearTimeout(timer);
        resolve(message);
      });
    });
  }

  async expectSilence(ms = 500) {
    await sleep(ms);
    assert.equal(this.queue.length, 0, `${this.name} got ${JSON.stringify(this.queue)}`);
  }

  /** Drops the connection without saying goodbye, like a network change does. */
  drop() {
    this.socket.close(4000, "test drop");
  }
}

const step = (text) => console.log(`- ${text}`);
// The Kotlin client always sends every field, null when unset.
const hello = (role, extra) => ({
  type: "hello", role, deviceName: `${role} device`, pin: null, roomCode: null, hostSecret: null, resumeToken: null, protocolVersion: 1, ...extra,
});

step("host opens a room");
const host = await new Client("host").connect();
host.send(hello("host", { pin: "123456" }));
const hostWelcome = await host.next();
assert.equal(hostWelcome.type, "welcome");
assert.match(hostWelcome.roomCode, /^[A-HJ-NP-Z2-9]{6}$/);
assert.ok(hostWelcome.iceServers.length > 0 && hostWelcome.resumeToken.length >= 22);

step("a ping is answered with a pong");
host.send({ type: "ping" });
assert.deepEqual(await host.next(), { type: "pong" });

step("wrong PIN and unknown room are refused");
const wrong = await new Client("wrong").connect();
wrong.send(hello("viewer", { roomCode: hostWelcome.roomCode, pin: "000000" }));
assert.deepEqual(await wrong.next(), { type: "error", code: "invalid_pin", message: "" });
const nowhere = await new Client("nowhere").connect();
nowhere.send(hello("viewer", { roomCode: "ZZZZZZ", pin: "123456" }));
assert.equal((await nowhere.next()).code, "room_not_found");

step("a viewer joins, is approved and negotiates");
const viewer = await new Client("viewer").connect();
viewer.send(hello("viewer", { roomCode: hostWelcome.roomCode.toLowerCase(), pin: "123456" }));
const viewerWelcome = await viewer.next();
assert.equal(viewerWelcome.hostId, hostWelcome.peerId);
assert.deepEqual(await host.next(), { type: "join_request", viewerId: viewerWelcome.peerId, deviceName: "viewer device" });
host.send({ type: "join_decision", viewerId: viewerWelcome.peerId, accepted: true });
assert.deepEqual(await viewer.next(), { type: "join_decision", viewerId: viewerWelcome.peerId, accepted: true });
host.send({ type: "offer", to: viewerWelcome.peerId, sdp: "v=0 offer" });
assert.deepEqual(await viewer.next(), { type: "offer", to: viewerWelcome.peerId, sdp: "v=0 offer", from: hostWelcome.peerId });
viewer.send({ type: "answer", to: hostWelcome.peerId, sdp: "v=0 answer" });
assert.equal((await host.next()).from, viewerWelcome.peerId);
viewer.send({ type: "ice", to: hostWelcome.peerId, candidate: "candidate:1", sdpMid: null, sdpMLineIndex: 0 });
assert.equal((await host.next()).candidate, "candidate:1");

step("a dropped viewer resumes without approval and gets what it missed");
viewer.drop();
await sleep(300);
host.send({ type: "offer", to: viewerWelcome.peerId, sdp: "restart" });
await host.expectSilence(300);
const again = await new Client("viewer2").connect();
again.send(hello("viewer", { resumeToken: viewerWelcome.resumeToken }));
const resumed = await again.next();
assert.equal(resumed.type, "welcome");
assert.equal(resumed.resumed, true);
assert.equal(resumed.peerId, viewerWelcome.peerId);
assert.deepEqual(await again.next(), { type: "offer", to: viewerWelcome.peerId, sdp: "restart", from: hostWelcome.peerId });
again.send({ type: "answer", to: hostWelcome.peerId, sdp: "restart answer" });
assert.equal((await host.next()).sdp, "restart answer");

if (testEviction) {
  step("rooms survive the object being evicted from memory (idle 25 s)");
  await sleep(25_000);
  host.send({ type: "ping" });
  assert.deepEqual(await host.next(), { type: "pong" });
  host.send({ type: "offer", to: viewerWelcome.peerId, sdp: "after idle" });
  assert.equal((await again.next()).sdp, "after idle");
  again.send({ type: "answer", to: hostWelcome.peerId, sdp: "answer after idle" });
  assert.equal((await host.next()).sdp, "answer after idle");

  step("a peer dropped while evicted still resumes");
  again.drop();
  await sleep(15_000);
  const third = await new Client("viewer3").connect();
  third.send(hello("viewer", { resumeToken: viewerWelcome.resumeToken }));
  assert.equal((await third.next()).resumed, true);
  again.socket = third.socket;
  again.next = third.next.bind(third);
  again.send = third.send.bind(third);
}

step("a viewer that leaves on purpose is announced at once");
again.send({ type: "leave" });
assert.deepEqual(await host.next(), { type: "peer_left", peerId: viewerWelcome.peerId });

step("the room ends when the host leaves");
const last = await new Client("last").connect();
last.send(hello("viewer", { roomCode: hostWelcome.roomCode, pin: "123456" }));
await last.next();
await host.next(); // join_request
host.send({ type: "leave" });
assert.deepEqual(await last.next(), { type: "session_ended" });

step("garbage is answered with protocol errors");
const junk = await new Client("junk").connect();
junk.send({ type: "offer", to: "x", sdp: "y" });
assert.equal((await junk.next()).code, "protocol");

console.log("all good");
process.exit(0);
