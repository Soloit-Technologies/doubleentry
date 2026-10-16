// POST /functions/v1/play-verify  {"purchaseToken": "…"}  (signed-in user)
//
// Called by the app after a Google Play purchase, and when it finds a purchase the server may
// not know yet (reinstall, new phone). Checks the token with Google and stores the subscription
// for the caller. The app acknowledges the purchase only after this succeeds, so a purchase the
// server never saw is refunded by Google after three days instead of being charged.
//
// Responses: 200 {"active": bool}, 400 invalid token, 401 not signed in,
// 409 {"error": "other_account"} when the purchase belongs to another DoubleEntry account.

import { fetchSubscription, InvalidPurchaseError, ownerOfToken, storeSubscription, toRow } from "../_shared/play.ts";
import { ownedByAnotherAccount } from "../_shared/subscriptions.ts";
import { callerId, json } from "../_shared/supabase.ts";

Deno.serve(async (request) => {
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" });
  const userId = await callerId(request);
  if (!userId) return json(401, { error: "not_signed_in" });

  const { purchaseToken } = await request.json().catch(() => ({})) as { purchaseToken?: string };
  if (!purchaseToken) return json(400, { error: "missing_token" });

  try {
    const purchase = await fetchSubscription(purchaseToken);
    // The app sets the obfuscated account id to the Supabase user id when it starts a purchase.
    const buyer = purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId;
    if (ownedByAnotherAccount(userId, buyer, await ownerOfToken(purchaseToken))) return json(409, { error: "other_account" });

    const row = toRow(purchase, purchaseToken);
    await storeSubscription(userId, row);
    return json(200, { active: Date.parse(row.expires_at) > Date.now() });
  } catch (e) {
    if (e instanceof InvalidPurchaseError) return json(400, { error: "invalid_token" });
    console.error("play-verify", e);
    return json(502, { error: "verification_failed" });
  }
});
