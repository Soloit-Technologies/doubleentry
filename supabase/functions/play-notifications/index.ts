// POST /functions/v1/play-notifications?secret=…  (Google Cloud Pub/Sub push subscription)
//
// Google Play Real-time Developer Notifications: renewals, cancellations, holds, expiries and
// refunds. Each one names a purchase token; the subscription is read again from Google and
// stored, so the server's expiry follows Google's without the app being opened.
//
// Pub/Sub can't send a Supabase JWT, so this function is deployed with verify_jwt = false and
// checks the shared secret PLAY_NOTIFICATIONS_SECRET in the push URL instead.
// A non-2xx response makes Pub/Sub retry later.

import { fetchSubscription, InvalidPurchaseError, ownerOfToken, storeSubscription, toRow } from "../_shared/play.ts";
import { json } from "../_shared/supabase.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface DeveloperNotification {
  subscriptionNotification?: { purchaseToken: string; notificationType: number };
  voidedPurchaseNotification?: { purchaseToken: string };
  testNotification?: unknown;
}

Deno.serve(async (request) => {
  const secret = Deno.env.get("PLAY_NOTIFICATIONS_SECRET");
  if (!secret || new URL(request.url).searchParams.get("secret") !== secret) return json(401, { error: "unauthorized" });

  const envelope = await request.json().catch(() => null) as { message?: { data?: string } } | null;
  const data = envelope?.message?.data;
  if (!data) return json(200, { ignored: "no data" });
  const notification = JSON.parse(atob(data)) as DeveloperNotification;
  const purchaseToken = notification.subscriptionNotification?.purchaseToken ?? notification.voidedPurchaseNotification?.purchaseToken;
  if (!purchaseToken) return json(200, { ignored: "not a subscription notification" });

  try {
    const purchase = await fetchSubscription(purchaseToken);
    // A token the app has verified before, else the account the app tagged the purchase with.
    const buyer = purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId;
    const userId = await ownerOfToken(purchaseToken) ?? (buyer && UUID.test(buyer) ? buyer : null);
    if (!userId) return json(200, { ignored: "unknown account" });
    await storeSubscription(userId, toRow(purchase, purchaseToken));
    return json(200, { stored: true });
  } catch (e) {
    // A token Google doesn't know will never succeed; don't let Pub/Sub retry it forever.
    if (e instanceof InvalidPurchaseError) return json(200, { ignored: "invalid token" });
    // E.g. a foreign-key error for a deleted account would also never succeed.
    if ((e as { code?: string }).code === "23503") return json(200, { ignored: "account deleted" });
    console.error("play-notifications", e);
    return json(500, { error: "failed" });
  }
});
