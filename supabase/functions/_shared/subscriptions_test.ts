// deno test supabase/functions  (the Supabase workflow runs it)

import { assertEquals } from "jsr:@std/assert@1";
import { notifiedUser, ownedByAnotherAccount, replacesStored, type SubscriptionRow, toRow } from "./subscriptions.ts";

const now = Date.parse("2026-10-16T12:00:00Z");
const day = 24 * 3600 * 1000;
const iso = (time: number) => new Date(time).toISOString();

function purchase(state: string, expiry: number) {
  return {
    subscriptionState: `SUBSCRIPTION_STATE_${state}`,
    lineItems: [{
      productId: "cloud_backup",
      expiryTime: iso(expiry),
      autoRenewingPlan: { autoRenewEnabled: state === "ACTIVE" },
      offerDetails: { basePlanId: "yearly" },
    }],
  };
}

Deno.test("an active subscription lasts until Google's expiry", () => {
  const row = toRow(purchase("ACTIVE", now + 30 * day), "token", now);
  assertEquals(row, {
    source: "google_play",
    product_id: "cloud_backup",
    base_plan_id: "yearly",
    purchase_token: "token",
    state: "active",
    expires_at: iso(now + 30 * day),
    auto_renewing: true,
  });
});

Deno.test("a canceled subscription keeps access until the paid period ends", () => {
  assertEquals(toRow(purchase("CANCELED", now + 10 * day), "token", now).expires_at, iso(now + 10 * day));
});

Deno.test("a lapsed card keeps uploads going for at least a day of grace", () => {
  assertEquals(toRow(purchase("IN_GRACE_PERIOD", now - day), "token", now).expires_at, iso(now + day));
  assertEquals(toRow(purchase("IN_GRACE_PERIOD", now + 5 * day), "token", now).expires_at, iso(now + 5 * day));
});

Deno.test("on hold, paused, expired or unknown ends access now, whatever the expiry says", () => {
  for (const state of ["ON_HOLD", "PAUSED", "EXPIRED", "PENDING_PURCHASE_CANCELED"]) {
    const row = toRow(purchase(state, now + 30 * day), "token", now);
    assertEquals(row.expires_at, iso(now), state);
  }
  assertEquals(toRow({}, "token", now).expires_at, iso(now));
  assertEquals(toRow({}, "token", now).state, "unspecified");
});

Deno.test("an expiry already past stays in the past", () => {
  assertEquals(toRow(purchase("EXPIRED", now - 3 * day), "token", now).expires_at, iso(now - 3 * day));
});

const row = (token: string, expires: number): SubscriptionRow => ({ ...toRow(purchase("ACTIVE", expires), token, now) });

Deno.test("complimentary access without an end is never replaced by a purchase", () => {
  assertEquals(replacesStored({ source: "complimentary", purchase_token: null, expires_at: null }, row("token", now + 365 * day)), false);
});

Deno.test("the same purchase always updates, also when it ends sooner (refund, expiry)", () => {
  const stored = { source: "google_play", purchase_token: "token", expires_at: iso(now + 30 * day) };
  assertEquals(replacesStored(stored, row("token", now)), true);
});

Deno.test("an old purchase restored on a new phone doesn't cut a current one short", () => {
  const stored = { source: "google_play", purchase_token: "current", expires_at: iso(now + 300 * day) };
  assertEquals(replacesStored(stored, row("old", now - 10 * day)), false);
  assertEquals(replacesStored(stored, row("newer", now + 365 * day)), true);
  assertEquals(replacesStored({ source: "complimentary", purchase_token: null, expires_at: iso(now - day) }, row("first", now + day)), true);
});

const me = "a0000000-0000-4000-8000-00000000000a";
const other = "b0000000-0000-4000-8000-00000000000b";

Deno.test("a purchase made by or stored for another account is refused", () => {
  assertEquals(ownedByAnotherAccount(me, undefined, null), false);
  assertEquals(ownedByAnotherAccount(me, me, me), false);
  assertEquals(ownedByAnotherAccount(me, other, null), true);
  assertEquals(ownedByAnotherAccount(me, undefined, other), true);
  assertEquals(ownedByAnotherAccount(me, me, other), true);
});

Deno.test("a notification goes to the user the token was verified for, else the tagged buyer", () => {
  assertEquals(notifiedUser(me, other), me);
  assertEquals(notifiedUser(null, other), other);
  assertEquals(notifiedUser(null, "not-a-user-id"), null);
  assertEquals(notifiedUser(null, undefined), null);
});
