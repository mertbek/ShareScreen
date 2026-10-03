import { describe, expect, it } from "vitest";
import { apkUrl } from "../src/download";

const base = "https://github.com/mertbek/ShareScreen/releases/download/v0.2.0/";

const release = {
  assets: [
    { name: "SHA256SUMS.txt", browser_download_url: base + "SHA256SUMS.txt" },
    { name: "ShareScreen-v0.2.0-lite.apk", browser_download_url: base + "ShareScreen-v0.2.0-lite.apk" },
    { name: "ShareScreen-v0.2.0.apk", browser_download_url: base + "ShareScreen-v0.2.0.apk" },
    { name: "ShareScreen-v0.2.0.msi", browser_download_url: base + "ShareScreen-v0.2.0.msi" },
  ],
};

describe("apkUrl", () => {
  it("finds the full edition", () => {
    expect(apkUrl(release, false)).toBe(base + "ShareScreen-v0.2.0.apk");
  });

  it("finds the lite edition", () => {
    expect(apkUrl(release, true)).toBe(base + "ShareScreen-v0.2.0-lite.apk");
  });

  it("is null when the release has no such file", () => {
    expect(apkUrl({ assets: [release.assets[0]] }, false)).toBeNull();
    expect(apkUrl({ assets: [] }, true)).toBeNull();
  });

  it("is null for a reply that is not a release", () => {
    expect(apkUrl(null, false)).toBeNull();
    expect(apkUrl("nope", false)).toBeNull();
    expect(apkUrl({ assets: "nope" }, false)).toBeNull();
    expect(apkUrl({ assets: [null, 3, { name: 4 }] }, false)).toBeNull();
  });

  it("never points outside the releases of the repository", () => {
    const elsewhere = { assets: [{ name: "ShareScreen-v9.apk", browser_download_url: "https://example.com/ShareScreen-v9.apk" }] };
    expect(apkUrl(elsewhere, false)).toBeNull();
  });
});
