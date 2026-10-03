import { describe, expect, it } from "vitest";
import { apkUrl, tagFrom } from "../src/download";

const base = "https://github.com/mertbek/ShareScreen/releases/download/v0.2.0/";

describe("tagFrom", () => {
  it("reads the tag out of the redirect", () => {
    expect(tagFrom("https://github.com/mertbek/ShareScreen/releases/tag/v0.2.0")).toBe("v0.2.0");
    expect(tagFrom("/mertbek/ShareScreen/releases/tag/v1.10.3")).toBe("v1.10.3");
  });

  it("is null when there is no release to point at", () => {
    expect(tagFrom(null)).toBeNull();
    expect(tagFrom("https://github.com/mertbek/ShareScreen/releases")).toBeNull();
    expect(tagFrom("https://github.com/login")).toBeNull();
  });

  it("only takes version tags", () => {
    expect(tagFrom("https://github.com/mertbek/ShareScreen/releases/tag/nightly")).toBeNull();
    expect(tagFrom("https://github.com/mertbek/ShareScreen/releases/tag/v0.2.0%2F..%2Fx")).toBeNull();
  });
});

describe("apkUrl", () => {
  it("names the full edition", () => {
    expect(apkUrl("v0.2.0", false)).toBe(base + "ShareScreen-v0.2.0.apk");
  });

  it("names the lite edition", () => {
    expect(apkUrl("v0.2.0", true)).toBe(base + "ShareScreen-v0.2.0-lite.apk");
  });

  it("is null without a tag", () => {
    expect(apkUrl(null, false)).toBeNull();
  });
});
