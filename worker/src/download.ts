const REPOSITORY = "mertbek/ShareScreen";
const DOWNLOADS = `https://github.com/${REPOSITORY}/releases/download/`;

/** Where people land when the newest release cannot be looked up. */
export const RELEASES_PAGE = `https://github.com/${REPOSITORY}/releases/latest`;

const CACHE_MILLIS = 10 * 60_000;

let cached: { tag: string; at: number } | undefined;

/** The tag a `releases/latest` redirect points at, for example `v0.2.0`, or null. */
export function tagFrom(location: string | null): string | null {
  const tag = location?.match(/\/releases\/tag\/([^/?#]+)$/)?.[1];
  return tag && /^v\d+(\.\d+)*$/.test(tag) ? tag : null;
}

/** The APK of a release as the release workflow names it: ShareScreen-v1.2.3.apk and ShareScreen-v1.2.3-lite.apk. */
export function apkUrl(tag: string | null, lite: boolean): string | null {
  return tag ? `${DOWNLOADS}${tag}/ShareScreen-${tag}${lite ? "-lite" : ""}.apk` : null;
}

async function latestTag(): Promise<string | null> {
  if (cached && Date.now() - cached.at < CACHE_MILLIS) return cached.tag;
  try {
    const response = await fetch(RELEASES_PAGE, { redirect: "manual" });
    const tag = tagFrom(response.headers.get("Location"));
    if (tag) cached = { tag, at: Date.now() };
  } catch {
    // Fall back to the last answer, if any.
  }
  return cached?.tag ?? null;
}

/** Sends the visitor to the APK of the newest release, or to the release page when there is none. */
export async function redirectToApk(lite: boolean): Promise<Response> {
  return Response.redirect(apkUrl(await latestTag(), lite) ?? RELEASES_PAGE, 302);
}
