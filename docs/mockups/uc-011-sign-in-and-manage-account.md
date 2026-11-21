# Screens and states: Sign In and Manage Account

Screen notes for UC-011: what the page shows, step by step and in each state. The behaviour is in the use case.

## Sign in

Anyone can sign in, to keep saved items on the account. The daily limits are per address, the same signed in or not (a larger limit for signed-in users is open: [S5.5.1](https://github.com/silvioincalza/helper2.ai/issues/293)). Nothing needs an account: search, the guide and saving in the browser work signed out. Administrators use the same sign-in on the back office host ([ADR 0036](../architecture/decisions/ADR-0036-identity-and-the-admin-host.md)).

1. Signed out, the header shows **Sign in** on every page (nothing when no provider is configured). It opens the sign-in page (`/signin`): why to sign in (saved items and settings on every device), then **Continue with GitHub**, one button per configured provider. The Saved page offers the provider buttons directly. Opened directly, the page returns to Home after sign-in; signed in, it links to the account; on a site with no provider it says sign-in is not offered here.
2. The page the user was on is kept in this tab. After the provider, the user comes back to that page, signed in; saved items merge ([UC-012](uc-012-save-resources.md)).
3. Signed in, the header shows the display name, which opens the account page, and **Sign out**. Sign-out returns to anonymous mode; search keeps working.

**Failure case:** sign-in fails or the provider is down → the user lands back with `?signin=failed`, and the page says "Sign-in did not work. Please try again." with a link to the sign-in page; anonymous use is unaffected.

**Delete the account.** The signed-in name in the header opens the account page (`/account`): who is signed in, the AI answers, AI setups and web searches left today with their reset time (from GET /me; a count the server does not give is not shown), the setting **Home page motion: radar and lists**, and **Delete my account**. A dialog asks to confirm, with focus on Cancel; Escape closes it and focus goes back to the button. On confirm the account and its saved items are deleted and the page reloads, signed out. If the deletion fails, an alert says so and the account stays. While the page asks who is signed in it shows a skeleton of the page. Signed out, the page says there is no account to delete, links to Privacy and, when the site has a provider, to the sign-in page.

| State | Behavior |
|---|---|
| Loading | While the page asks who is signed in, the sign-in and account pages show a skeleton of the page. |
| Success | The user comes back to the page they were on, signed in; saved items merge ([UC-012](uc-012-save-resources.md)). |
| Empty | No provider is configured: the header shows no **Sign in**, and the sign-in page says sign-in is not offered here. Signed out, the account page says there is no account to delete. |
| Partial | Does not apply. |
| Error | Sign-in fails or the provider is down: `?signin=failed` and "Sign-in did not work. Please try again."; anonymous use is unaffected. A deletion that fails: an alert says so and the account stays. |
