// POST /functions/v1/delete-account  (signed-in user)
//
// Deletes the caller's account and, through `on delete cascade`, every row they own: the cloud
// copy of the ledger and the subscription record. Google Play requires apps that create
// accounts to offer this in the app. A Play subscription is not cancelled by this; the app
// tells the user to cancel it in Google Play first.

import { admin, callerId, json } from "../_shared/supabase.ts";

Deno.serve(async (request) => {
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" });
  const userId = await callerId(request);
  if (!userId) return json(401, { error: "not_signed_in" });

  const { error } = await admin.auth.admin.deleteUser(userId);
  if (error) {
    console.error("delete-account", error);
    return json(500, { error: "delete_failed" });
  }
  return json(200, { deleted: true });
});
