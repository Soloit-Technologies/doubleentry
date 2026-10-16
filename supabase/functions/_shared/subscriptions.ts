// Turning Google Play subscription purchases into cloud_subscriptions rows, and which row wins.
// No I/O and no imports, so it runs under `deno test` without secrets (subscriptions_test.ts).

/** The parts of a SubscriptionPurchaseV2 this backend reads. */
export interface SubscriptionPurchase {
  subscriptionState?: string;
  lineItems?: {
    productId?: string;
    expiryTime?: string;
    autoRenewingPlan?: { autoRenewEnabled?: boolean };
    offerDetails?: { basePlanId?: string; offerId?: string };
  }[];
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
}

export interface SubscriptionRow {
  source: "google_play";
  product_id: string | null;
  base_plan_id: string | null;
  purchase_token: string;
  state: string;
  expires_at: string;
  auto_renewing: boolean;
}

/** States in which the subscriber keeps access until the line item's expiry time. */
const ENTITLED = new Set(["active", "in_grace_period", "canceled"]);

export function toRow(purchase: SubscriptionPurchase, purchaseToken: string, now = Date.now()): SubscriptionRow {
  const item = purchase.lineItems?.[0];
  const state = (purchase.subscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED").replace("SUBSCRIPTION_STATE_", "").toLowerCase();
  const expiry = item?.expiryTime ? Date.parse(item.expiryTime) : now;
  let expiresAt: number;
  if (!ENTITLED.has(state)) expiresAt = Math.min(expiry, now);
  // Keep a lapsed card from cutting off uploads mid-grace if the expiry hasn't moved yet.
  else if (state === "in_grace_period") expiresAt = Math.max(expiry, now + 24 * 3600 * 1000);
  else expiresAt = expiry;
  return {
    source: "google_play",
    product_id: item?.productId ?? null,
    base_plan_id: item?.offerDetails?.basePlanId ?? null,
    purchase_token: purchaseToken,
    state,
    expires_at: new Date(expiresAt).toISOString(),
    auto_renewing: item?.autoRenewingPlan?.autoRenewEnabled ?? false,
  };
}

/** The parts of the stored row [replacesStored] compares. */
export interface StoredSubscription {
  source: string;
  purchase_token: string | null;
  expires_at: string | null;
}

/**
 * Whether [row] replaces the subscription stored for its user. Complimentary access without an
 * end is never replaced, and a different purchase only replaces the stored one when it lasts at
 * least as long (an old, expired purchase restored on a new phone doesn't cut a current one short).
 */
export function replacesStored(existing: StoredSubscription, row: SubscriptionRow): boolean {
  if (existing.source === "complimentary" && existing.expires_at === null) return false;
  const samePurchase = existing.purchase_token === row.purchase_token;
  return samePurchase || !existing.expires_at || Date.parse(existing.expires_at) <= Date.parse(row.expires_at);
}

/**
 * For play-verify: whether a purchase [userId] sends in belongs to another account. The app tags
 * a purchase with the buyer's user id ([buyer]); a token is stored for one user only ([storedOwner]).
 */
export function ownedByAnotherAccount(userId: string, buyer: string | undefined, storedOwner: string | null): boolean {
  return (!!buyer && buyer !== userId) || (!!storedOwner && storedOwner !== userId);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * For play-notifications: whose subscription a notification is about. The user the token was
 * verified for, else the account the app tagged the purchase with; null when neither is known.
 */
export function notifiedUser(storedOwner: string | null, buyer: string | undefined): string | null {
  return storedOwner ?? (buyer && UUID.test(buyer) ? buyer : null);
}
