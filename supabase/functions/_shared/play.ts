// Google Play Developer API: reads a subscription purchase and turns it into a
// cloud_subscriptions row. Needs the secret GOOGLE_PLAY_SERVICE_ACCOUNT (the service account
// key JSON; the account needs "View financial data" and "Manage orders and subscriptions" for
// the app in Play Console → Users and permissions).

import { admin } from "./supabase.ts";
import { replacesStored, type SubscriptionPurchase, type SubscriptionRow } from "./subscriptions.ts";

export { toRow } from "./subscriptions.ts";

const PACKAGE_NAME = Deno.env.get("PLAY_PACKAGE_NAME") ?? "com.soloit.doubleentry";

/** Google said the token is unknown or malformed (as opposed to Google being unreachable). */
export class InvalidPurchaseError extends Error {}

let cachedToken: { value: string; expiresAt: number } | null = null;

function base64Url(bytes: Uint8Array): string {
  let text = "";
  for (const b of bytes) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** An OAuth access token for the Android Publisher API, from a self-signed service-account JWT. */
async function googleAccessToken(): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) return cachedToken.value;
  const raw = Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT");
  if (!raw) throw new Error("GOOGLE_PLAY_SERVICE_ACCOUNT is not set");
  const key = JSON.parse(raw) as { client_email: string; private_key: string };

  const now = Math.floor(Date.now() / 1000);
  const encode = (value: unknown) => base64Url(new TextEncoder().encode(JSON.stringify(value)));
  const unsigned = `${encode({ alg: "RS256", typ: "JWT" })}.${
    encode({
      iss: key.client_email,
      scope: "https://www.googleapis.com/auth/androidpublisher",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    })
  }`;
  const der = Uint8Array.from(atob(key.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "")), (c) => c.charCodeAt(0));
  const signingKey = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", signingKey, new TextEncoder().encode(unsigned)));

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64Url(signature)}`,
    }),
  });
  if (!response.ok) throw new Error(`Google token request failed: ${response.status} ${await response.text()}`);
  const body = await response.json() as { access_token: string; expires_in: number };
  cachedToken = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return body.access_token;
}

/** `GET purchases.subscriptionsv2.get` for [purchaseToken]. */
export async function fetchSubscription(purchaseToken: string): Promise<SubscriptionPurchase> {
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const response = await fetch(url, { headers: { authorization: `Bearer ${await googleAccessToken()}` } });
  if (response.status === 400 || response.status === 404 || response.status === 410) {
    throw new InvalidPurchaseError(`Unknown purchase token: ${response.status} ${await response.text()}`);
  }
  if (!response.ok) throw new Error(`Play Developer API: ${response.status} ${await response.text()}`);
  return await response.json() as SubscriptionPurchase;
}

/** Stores [row] as [userId]'s subscription, unless the stored one wins ([replacesStored]). */
export async function storeSubscription(userId: string, row: SubscriptionRow): Promise<void> {
  const { data: existing, error } = await admin
    .from("cloud_subscriptions")
    .select("source, purchase_token, expires_at")
    .eq("user_id", userId)
    .maybeSingle();
  if (error) throw error;
  if (existing && !replacesStored(existing, row)) return;
  const { error: upsertError } = await admin.from("cloud_subscriptions").upsert({ user_id: userId, ...row }, { onConflict: "user_id" });
  if (upsertError) throw upsertError;
}

/** The user a purchase token is already stored for, if any. */
export async function ownerOfToken(purchaseToken: string): Promise<string | null> {
  const { data, error } = await admin.from("cloud_subscriptions").select("user_id").eq("purchase_token", purchaseToken).maybeSingle();
  if (error) throw error;
  return data?.user_id ?? null;
}
