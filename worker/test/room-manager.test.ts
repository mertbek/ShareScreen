import { describe, expect, it } from "vitest";
import { decode, encode, PING_TEXT, PONG_TEXT } from "../src/protocol";
import type { Hello, IceServerConfig, Message } from "../src/protocol";
import { Config, DEFAULT_CONFIG, Member, PeerLink, RoomManager } from "../src/room-manager";

class FakeLink implements PeerLink {
  received: Message[] = [];
  closed = false;
  send(message: Message) {
    this.received.push(message);
  }
  close() {
    this.closed = true;
  }
  takeAll(): Message[] {
    const messages = this.received;
    this.received = [];
    return messages;
  }
}

const PIN = "482913";
const GRACE = 30_000;
const NO_ICE: IceServerConfig[] = [];

function setup(config: Partial<Config> = {}) {
  let counter = 0;
  const clock = { now: 0 };
  const manager = new RoomManager(
    { ...DEFAULT_CONFIG, resumeGraceMillis: GRACE, ...config },
    {
      newPeerId: () => `peer${++counter}`,
      newRoomCode: () => `ROOM${counter}`.padEnd(6, "X").slice(0, 6),
      newResumeToken: () => `token${++counter}`,
      now: () => clock.now,
    },
  );
  return { manager, clock };
}

const hostHello = (pin: string | undefined = PIN): Hello => ({ type: "hello", role: "host", deviceName: "Host phone", pin });
const viewerHello = (roomCode: string, pin: string | undefined = PIN, deviceName = "Viewer phone"): Hello => ({
  type: "hello",
  role: "viewer",
  deviceName,
  roomCode,
  pin,
});

// Passing undefined would pick the default PIN, so a room without one is asked for explicitly.
const noPinHostHello = (): Hello => ({ ...hostHello(), pin: undefined });
const noPinViewerHello = (roomCode: string, deviceName = "Viewer phone"): Hello => ({
  ...viewerHello(roomCode, PIN, deviceName),
  pin: undefined,
});

function roomWithViewer() {
  const s = setup();
  const hostLink = new FakeLink();
  const host = s.manager.join(hostLink, hostHello(), NO_ICE)!;
  const viewerLink = new FakeLink();
  const viewer = s.manager.join(viewerLink, viewerHello(host.roomCode), NO_ICE)!;
  hostLink.takeAll();
  viewerLink.takeAll();
  return { ...s, host, hostLink, viewer, viewerLink };
}

const errorCode = (message: Message | undefined) => (message?.type === "error" ? message.code : undefined);

function guessWrong(manager: RoomManager, roomCode: string, times: number, address: string | null) {
  for (let attempt = 0; attempt < times; attempt++) {
    const link = new FakeLink();
    manager.join(link, viewerHello(roomCode, `00000${attempt}`), NO_ICE, address);
    expect(errorCode(link.received[0])).toBe("invalid_pin");
  }
}

describe("RoomManager", () => {
  it("answers a ping with a pong and nothing else", () => {
    const { host, hostLink, viewerLink, manager } = roomWithViewer();
    manager.handle(host, { type: "ping" });
    expect(hostLink.takeAll()).toEqual([{ type: "pong" }]);
    expect(viewerLink.takeAll()).toEqual([]);
  });

  it("reads the ping the apps send, and the text the runtime answers by itself is the same", () => {
    expect(decode(`{"type":"ping"}`)).toEqual({ type: "ping" });
    expect(PING_TEXT).toBe(`{"type":"ping"}`);
    expect(PONG_TEXT).toBe(`{"type":"pong"}`);
    expect(encode({ type: "ping" })).toBe(PING_TEXT);
  });

  it("a host opens a room and gets a welcome with the ICE servers and a resume token", () => {
    const { manager } = setup();
    const link = new FakeLink();
    const ice = [{ urls: ["turn:turn.test"], username: "u", credential: "c" }];
    const host = manager.join(link, hostHello(), ice)!;

    expect(link.received).toEqual([
      { type: "welcome", peerId: host.id, roomCode: host.roomCode, hostId: host.id, iceServers: ice, resumeToken: host.resumeToken, resumed: false },
    ]);
  });

  it("a viewer with an unknown room is refused, and the code is matched case-insensitively", () => {
    const { manager } = setup();
    const link = new FakeLink();
    expect(manager.join(link, viewerHello("NOPE22"), NO_ICE)).toBeNull();
    expect(errorCode(link.received[0])).toBe("room_not_found");
    expect(link.closed).toBe(true);

    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    expect(manager.join(new FakeLink(), viewerHello(` ${host.roomCode.toLowerCase()} `), NO_ICE)).not.toBeNull();
  });

  it("a wrong PIN is refused, and guessing locks the room for a minute", () => {
    const { manager, clock } = setup({ maxWrongPinsPerMinute: 3 });
    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    for (let attempt = 0; attempt < 3; attempt++) {
      clock.now = attempt * 1000;
      const link = new FakeLink();
      manager.join(link, viewerHello(host.roomCode, `00000${attempt}`), NO_ICE);
      expect(errorCode(link.received[0])).toBe("invalid_pin");
    }
    clock.now = 30_000;
    const locked = new FakeLink();
    expect(manager.join(locked, viewerHello(host.roomCode), NO_ICE)).toBeNull();
    expect(errorCode(locked.received[0])).toBe("too_many_attempts");

    clock.now = 61_000;
    expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)).not.toBeNull();
  });

  it("a room without a PIN lets anyone with the code ask to join", () => {
    const { manager } = setup();
    const host = manager.join(new FakeLink(), noPinHostHello(), NO_ICE)!;
    expect(manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE)).not.toBeNull();
  });

  it("a viewer's join is announced to the host", () => {
    const { host, hostLink, manager } = roomWithViewer();
    const link = new FakeLink();
    const second = manager.join(link, viewerHello(host.roomCode, PIN, "Second"), NO_ICE)!;
    expect(hostLink.received).toEqual([{ type: "join_request", viewerId: second.id, deviceName: "Second" }]);
  });

  it("offers are not relayed before the host approves", () => {
    const { manager, host, hostLink, viewer, viewerLink } = roomWithViewer();
    manager.handle(host, { type: "offer", to: viewer.id, sdp: "v=0", from: "" });
    expect(viewerLink.received).toEqual([]);
    expect(errorCode(hostLink.received[0])).toBe("protocol");
  });

  it("an approved viewer negotiates with the host, and sender ids cannot be spoofed", () => {
    const { manager, host, hostLink, viewer, viewerLink } = roomWithViewer();
    manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
    expect(viewerLink.takeAll()).toEqual([{ type: "join_decision", viewerId: viewer.id, accepted: true }]);

    manager.handle(host, { type: "offer", to: viewer.id, sdp: "offer", from: "spoofed" });
    expect(viewerLink.takeAll()).toEqual([{ type: "offer", to: viewer.id, sdp: "offer", from: host.id }]);

    manager.handle(viewer, { type: "answer", to: host.id, sdp: "answer", from: "" });
    manager.handle(viewer, { type: "ice", to: host.id, candidate: "c", sdpMid: "0", sdpMLineIndex: 0, from: "" });
    expect(hostLink.takeAll()).toEqual([
      { type: "answer", to: host.id, sdp: "answer", from: viewer.id },
      { type: "ice", to: host.id, candidate: "c", sdpMid: "0", sdpMLineIndex: 0, from: viewer.id },
    ]);
  });

  it("viewers cannot message each other or send host-only messages", () => {
    const { manager, host, viewer, viewerLink } = roomWithViewer();
    manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
    const otherLink = new FakeLink();
    const other = manager.join(otherLink, viewerHello(host.roomCode, PIN, "Other"), NO_ICE)!;
    manager.handle(host, { type: "join_decision", viewerId: other.id, accepted: true });
    otherLink.takeAll();
    viewerLink.takeAll();

    manager.handle(viewer, { type: "offer", to: other.id, sdp: "x", from: "" });
    expect(otherLink.received).toEqual([]);
    expect(errorCode(viewerLink.takeAll()[0])).toBe("protocol");

    manager.handle(viewer, { type: "join_decision", viewerId: viewer.id, accepted: true });
    expect(errorCode(viewerLink.takeAll()[0])).toBe("protocol");
  });

  it("a rejected viewer is told and disconnected, without telling the host", () => {
    const { manager, host, hostLink, viewer, viewerLink } = roomWithViewer();
    manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: false });
    expect(errorCode(viewerLink.received[0])).toBe("rejected");
    expect(viewerLink.closed).toBe(true);
    expect(hostLink.received).toEqual([]);
  });

  it("the room is full when the maximum number of viewers are watching", () => {
    const { manager } = setup({ maxViewersPerRoom: 1 });
    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    const viewer = manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)!;
    manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
    const link = new FakeLink();
    expect(manager.join(link, viewerHello(host.roomCode), NO_ICE)).toBeNull();
    expect(errorCode(link.received[0])).toBe("room_full");
  });

  it("requests waiting for approval do not fill the room", () => {
    const { manager } = setup({ maxViewersPerRoom: 1 });
    const hostLink = new FakeLink();
    const host = manager.join(hostLink, hostHello(), NO_ICE)!;
    const links = [new FakeLink(), new FakeLink(), new FakeLink()];
    const viewers = links.map((link, i) => manager.join(link, viewerHello(host.roomCode, PIN, `Viewer ${i}`), NO_ICE)!);
    hostLink.takeAll();

    manager.handle(host, { type: "join_decision", viewerId: viewers[1].id, accepted: true });

    expect(links[1].received.slice(1)).toEqual([{ type: "join_decision", viewerId: viewers[1].id, accepted: true }]);
    for (const i of [0, 2]) {
      expect(errorCode(links[i].received[1])).toBe("room_full");
      expect(links[i].closed).toBe(true);
    }
    expect(hostLink.received).toEqual([
      { type: "peer_left", peerId: viewers[0].id },
      { type: "peer_left", peerId: viewers[2].id },
    ]);
  });

  it("a place freed by a viewer who leaves takes a new request", () => {
    const { manager } = setup({ maxViewersPerRoom: 1 });
    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    const viewer = manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)!;
    manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });

    manager.handle(viewer, { type: "leave" });

    expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)).not.toBeNull();
  });

  it("too many waiting requests are turned away", () => {
    const { manager } = setup({ maxWaitingPerRoom: 2 });
    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    for (let i = 0; i < 2; i++) expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)).not.toBeNull();
    const link = new FakeLink();
    expect(manager.join(link, viewerHello(host.roomCode), NO_ICE)).toBeNull();
    expect(errorCode(link.received[0])).toBe("room_full");
  });

  describe("without a PIN", () => {
    function open() {
      const s = setup();
      const hostLink = new FakeLink();
      const host = s.manager.join(hostLink, noPinHostHello(), NO_ICE)!;
      hostLink.takeAll();
      return { ...s, host, hostLink };
    }

    it("a device asking again replaces its waiting request", () => {
      const { manager, host, hostLink } = open();
      const firstLink = new FakeLink();
      const first = manager.join(firstLink, noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")!;
      manager.join(new FakeLink(), noPinViewerHello(host.roomCode, "Other"), NO_ICE, "10.0.0.6");
      hostLink.takeAll();

      const second = manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")!;

      expect(firstLink.closed).toBe(true);
      expect(hostLink.received).toEqual([
        { type: "peer_left", peerId: first.id },
        { type: "join_request", viewerId: second.id, deviceName: "Viewer phone" },
      ]);
      manager.handle(host, { type: "join_decision", viewerId: first.id, accepted: true });
      expect(firstLink.received.some((m) => m.type === "join_decision")).toBe(false);
    });

    it("a device the host turned down waits a minute before asking again", () => {
      const { manager, clock, host } = open();
      const viewer = manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")!;
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: false });

      clock.now = 59_000;
      const again = new FakeLink();
      expect(manager.join(again, noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")).toBeNull();
      expect(errorCode(again.received[0])).toBe("rejected");
      expect(manager.join(new FakeLink(), noPinViewerHello(host.roomCode, "Other"), NO_ICE, "10.0.0.6")).not.toBeNull();

      clock.now = 60_000;
      expect(manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")).not.toBeNull();
    });

    it("a removed viewer can ask again", () => {
      const { manager, host } = open();
      const viewer = manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")!;
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
      manager.handle(host, { type: "kick", viewerId: viewer.id });

      expect(manager.join(new FakeLink(), noPinViewerHello(host.roomCode), NO_ICE, "10.0.0.5")).not.toBeNull();
    });
  });

  it("with a PIN, requests from one address wait side by side", () => {
    const { manager } = setup();
    const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
    const first = manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE, "10.0.0.5")!;
    manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE, "10.0.0.5");
    manager.handle(host, { type: "join_decision", viewerId: first.id, accepted: false });

    expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE, "10.0.0.5")).not.toBeNull();
  });

  describe("guessing the PIN", () => {
    it("locks the guesser out for a minute and nobody else", () => {
      const { manager, clock } = setup({ maxWrongPinsPerMinute: 3 });
      const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
      guessWrong(manager, host.roomCode, 3, "10.0.0.66");

      const locked = new FakeLink();
      expect(manager.join(locked, viewerHello(host.roomCode), NO_ICE, "10.0.0.66")).toBeNull();
      expect(errorCode(locked.received[0])).toBe("too_many_attempts");
      expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE, "10.0.0.7")).not.toBeNull();

      clock.now = 61_000;
      expect(manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE, "10.0.0.66")).not.toBeNull();
    });

    it("gives each new address a single guess while the room is locked", () => {
      const { manager } = setup({ maxWrongPinsPerMinute: 3 });
      const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
      guessWrong(manager, host.roomCode, 3, "10.0.0.66");
      guessWrong(manager, host.roomCode, 1, "10.0.0.67");

      const again = new FakeLink();
      expect(manager.join(again, viewerHello(host.roomCode, "999999"), NO_ICE, "10.0.0.67")).toBeNull();
      expect(errorCode(again.received[0])).toBe("too_many_attempts");
    });

    it("from many addresses locks the room for everyone", () => {
      const { manager } = setup({ maxWrongPinsPerMinute: 3 });
      const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
      for (let i = 0; i < 256; i++) guessWrong(manager, host.roomCode, 1, `10.0.${Math.floor(i / 250)}.${i % 250}`);

      const locked = new FakeLink();
      expect(manager.join(locked, viewerHello(host.roomCode), NO_ICE, "10.0.9.1")).toBeNull();
      expect(errorCode(locked.received[0])).toBe("too_many_attempts");
    });

    it("from connections without an address shares one lock", () => {
      const { manager } = setup({ maxWrongPinsPerMinute: 3 });
      const host = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
      guessWrong(manager, host.roomCode, 3, null);

      const locked = new FakeLink();
      expect(manager.join(locked, viewerHello(host.roomCode), NO_ICE)).toBeNull();
      expect(errorCode(locked.received[0])).toBe("too_many_attempts");
    });
  });

  it("a pass in a hello is dropped, since the internet server asks for the PIN every time", () => {
    const hello = decode(
      JSON.stringify({ type: "hello", role: "viewer", deviceName: "V", roomCode: "ABCDEF", pass: { key: "k", counter: 1, proof: "p" } }),
    );
    expect(hello).toMatchObject({ type: "hello", role: "viewer", roomCode: "ABCDEF" });
    expect(hello).not.toHaveProperty("pass");
  });

  it("the host hears when a viewer leaves or is kicked", () => {
    const { manager, host, hostLink, viewer, viewerLink } = roomWithViewer();
    manager.handle(host, { type: "kick", viewerId: viewer.id });
    expect(errorCode(viewerLink.received[0])).toBe("kicked");
    expect(viewerLink.closed).toBe(true);
    expect(hostLink.takeAll()).toEqual([{ type: "peer_left", peerId: viewer.id }]);

    const again = manager.join(new FakeLink(), viewerHello(host.roomCode), NO_ICE)!;
    hostLink.takeAll();
    manager.handle(again, { type: "leave" });
    expect(hostLink.received).toEqual([{ type: "peer_left", peerId: again.id }]);
  });

  it("viewers are told when the host leaves, and the room is gone", () => {
    const { manager, host, viewerLink } = roomWithViewer();
    const roomCode = host.roomCode;
    manager.handle(host, { type: "leave" });
    expect(viewerLink.received).toEqual([{ type: "session_ended" }]);
    expect(viewerLink.closed).toBe(true);

    const link = new FakeLink();
    expect(manager.join(link, viewerHello(roomCode), NO_ICE)).toBeNull();
  });

  it("an unsupported protocol version is refused", () => {
    const { manager } = setup();
    const link = new FakeLink();
    expect(manager.join(link, { ...hostHello(), protocolVersion: 2 }, NO_ICE)).toBeNull();
    expect(errorCode(link.received[0])).toBe("unsupported_version");
  });

  describe("resuming", () => {
    it("a dropped viewer keeps its place and resumes without approval, getting what it missed", () => {
      const { manager, clock, host, hostLink, viewer, viewerLink } = roomWithViewer();
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
      viewerLink.takeAll();

      manager.disconnected(viewer, viewerLink);
      manager.handle(host, { type: "offer", to: viewer.id, sdp: "restart", from: "" });
      expect(hostLink.received).toEqual([]);

      clock.now = GRACE - 1;
      const newLink = new FakeLink();
      const resumed = manager.join(newLink, { type: "hello", role: "viewer", deviceName: "Viewer phone", resumeToken: viewer.resumeToken }, NO_ICE);
      expect(resumed).toBe(viewer);
      expect(newLink.received).toEqual([
        { type: "welcome", peerId: viewer.id, roomCode: host.roomCode, hostId: host.id, iceServers: [], resumeToken: viewer.resumeToken, resumed: true },
        { type: "offer", to: viewer.id, sdp: "restart", from: host.id },
      ]);
    });

    it("a dropped host keeps its room and viewers, and hears what it missed", () => {
      const { manager, host, hostLink, viewer } = roomWithViewer();
      manager.disconnected(host, hostLink);
      manager.handle(viewer, { type: "leave" });

      const newLink = new FakeLink();
      expect(manager.join(newLink, { type: "hello", role: "host", deviceName: "Host phone", resumeToken: host.resumeToken }, NO_ICE)).toBe(host);
      expect(newLink.received[1]).toEqual({ type: "peer_left", peerId: viewer.id });
    });

    it("a viewer that does not resume in time is gone", () => {
      const { manager, clock, host, hostLink, viewer, viewerLink } = roomWithViewer();
      manager.disconnected(viewer, viewerLink);
      clock.now = GRACE;
      manager.expire();

      expect(hostLink.received).toEqual([{ type: "peer_left", peerId: viewer.id }]);
      const late = new FakeLink();
      expect(manager.join(late, { type: "hello", role: "viewer", deviceName: "V", resumeToken: viewer.resumeToken }, NO_ICE)).toBeNull();
      expect(errorCode(late.received[0])).toBe("resume_failed");
      expect(host.roomCode).toBeTruthy();
    });

    it("a host that does not resume in time ends the session", () => {
      const { manager, clock, host, hostLink, viewerLink } = roomWithViewer();
      manager.disconnected(host, hostLink);
      clock.now = GRACE;
      manager.expire();
      expect(viewerLink.received).toEqual([{ type: "session_ended" }]);
      expect(viewerLink.closed).toBe(true);
    });

    it("resuming replaces a connection that has not noticed it is gone", () => {
      const { manager, host, viewer, viewerLink } = roomWithViewer();
      const newLink = new FakeLink();
      manager.join(newLink, { type: "hello", role: "viewer", deviceName: "V", resumeToken: viewer.resumeToken }, NO_ICE);
      expect(viewerLink.closed).toBe(true);

      // The old connection's end must not detach the member from the new one.
      manager.disconnected(viewer, viewerLink);
      newLink.takeAll();
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
      expect(newLink.received).toEqual([{ type: "join_decision", viewerId: viewer.id, accepted: true }]);
    });

    it("a resume token only works for its own role", () => {
      const { manager, viewer } = roomWithViewer();
      const link = new FakeLink();
      expect(manager.join(link, { type: "hello", role: "host", deviceName: "H", resumeToken: viewer.resumeToken }, NO_ICE)).toBeNull();
      expect(errorCode(link.received[0])).toBe("resume_failed");
    });
  });

  describe("persistence", () => {
    it("a snapshot restores rooms, members and what was missed", () => {
      const { manager, host, viewer, viewerLink, clock } = roomWithViewer();
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
      manager.disconnected(viewer, viewerLink);
      manager.handle(host, { type: "offer", to: viewer.id, sdp: "while away", from: "" });
      const snapshot = JSON.parse(JSON.stringify(manager.snapshot()));

      const restored = new RoomManager(
        { ...DEFAULT_CONFIG, resumeGraceMillis: GRACE },
        { newPeerId: () => "x", newRoomCode: () => "YYYYYY", newResumeToken: () => "t", now: () => clock.now },
        snapshot,
      );
      const restoredViewer = restored.member(viewer.id) as Member;
      expect(restoredViewer.approved).toBe(true);
      expect(restoredViewer.missed).toHaveLength(1);

      const link = new FakeLink();
      expect(restored.join(link, { type: "hello", role: "viewer", deviceName: "V", resumeToken: viewer.resumeToken }, NO_ICE)).toBe(restoredViewer);
      expect(link.received.at(-1)).toEqual({ type: "offer", to: viewer.id, sdp: "while away", from: host.id });
    });

    it("a snapshot keeps who guessed and who was turned down", () => {
      const { manager, clock } = setup({ maxWrongPinsPerMinute: 3 });
      const pinHost = manager.join(new FakeLink(), hostHello(), NO_ICE)!;
      guessWrong(manager, pinHost.roomCode, 3, "10.0.0.66");
      const openHost = manager.join(new FakeLink(), noPinHostHello(), NO_ICE)!;
      const viewer = manager.join(new FakeLink(), noPinViewerHello(openHost.roomCode), NO_ICE, "10.0.0.5")!;
      manager.handle(openHost, { type: "join_decision", viewerId: viewer.id, accepted: false });

      const restored = new RoomManager(
        { ...DEFAULT_CONFIG, maxWrongPinsPerMinute: 3, resumeGraceMillis: GRACE },
        { newPeerId: () => "x", newRoomCode: () => "YYYYYY", newResumeToken: () => "t", now: () => clock.now },
        JSON.parse(JSON.stringify(manager.snapshot())),
      );

      expect(restored.join(new FakeLink(), viewerHello(pinHost.roomCode), NO_ICE, "10.0.0.66")).toBeNull();
      expect(restored.join(new FakeLink(), viewerHello(pinHost.roomCode), NO_ICE, "10.0.0.7")).not.toBeNull();
      expect(restored.join(new FakeLink(), noPinViewerHello(openHost.roomCode), NO_ICE, "10.0.0.5")).toBeNull();
    });

    it("a snapshot written before addresses were tracked still restores", () => {
      const { manager, clock } = roomWithViewer();
      const snapshot = JSON.parse(JSON.stringify(manager.snapshot()));
      for (const room of snapshot.rooms) {
        delete room.wrongPinAddresses;
        delete room.refusedAddresses;
      }
      for (const member of snapshot.members) delete member.address;

      const restored = new RoomManager(
        { ...DEFAULT_CONFIG, resumeGraceMillis: GRACE },
        { newPeerId: () => "x", newRoomCode: () => "YYYYYY", newResumeToken: () => "t", now: () => clock.now },
        snapshot,
      );

      expect(restored.allMembers().next().done).toBe(false);
      expect(restored.join(new FakeLink(), viewerHello(restored.member([...manager.allMembers()][0].id)!.roomCode), NO_ICE)).not.toBeNull();
    });

    it("changes are reported once, message relays are not changes", () => {
      const { manager, host, viewer } = roomWithViewer();
      expect(manager.takeDirty()).toBe(true);
      expect(manager.takeDirty()).toBe(false);
      manager.handle(host, { type: "join_decision", viewerId: viewer.id, accepted: true });
      manager.takeDirty();
      manager.handle(host, { type: "offer", to: viewer.id, sdp: "x", from: "" });
      expect(manager.takeDirty()).toBe(false);
    });
  });
});
