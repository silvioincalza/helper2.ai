# AdSense go-live runbook

How the Google ads start on prod, step by step, and how to stop them.
[ADR 0042](../../docs/architecture/decisions/ADR-0042-monetisation.md) says why each rule is there, and
[RUNBOOK-release.md](RUNBOOK-release.md) is the release itself. Story:
[S8.6.3](https://github.com/silvioincalza/helper2.ai/issues/872).

**(Owner)** marks a step only the owner can do. Commands run from the repository root, with `gh` signed in as the
owner.

## What turns the ads on

Prod sets the publisher today (`app.monetisation.adsenseClient`), so Google can check the site. No ad shows until
an ad unit is set and the ads switch is on.

The switch is in the back office: **Settings > Switches you can change > "Google ads"**, `true` or `false` (runtime switch `ads.enabled`, default
`SA_ADS_ENABLED`, `false`). It needs no release: every replica follows within about 20 seconds, and the audit trail
keeps who changed it and why. While it is 0 the ad units are ignored, so they can reach prod days before the ads
start. One ad unit per place:

| Place | Helm value (`app.monetisation.`) | Setting | Ad unit in AdSense |
|---|---|---|---|
| Resource page | `adsenseSlot` | `SA_ADS_ADSENSE_SLOT` | `searchavo-resource-page` |
| Below the search results | `adsenseSlotSearch` | `SA_ADS_ADSENSE_SLOT_SEARCH` | `searchavo-search-results` |
| Below a guide answer | `adsenseSlotGuide` | `SA_ADS_ADSENSE_SLOT_GUIDE` | `searchavo-guide-answer` |

The switch and the first ad unit together also turn on Google's consent tool and the nonce CSP for the whole public
site. An empty ad unit leaves that place without an ad.

## 1. Before

Each line must be true. The script asks for the three that only the AdSense account shows.

| What | Check | Who |
|---|---|---|
| The pull requests that add the search and guide settings are merged | `yq '.app.monetisation' deploy/helm/searchavo/values.yaml` lists `adsenseSlotSearch` and `adsenseSlotGuide` | **(Owner)** |
| Google approved the site | AdSense > Sites: searchavo.ai shows "Ready" | **(Owner)** |
| Auto ads and overlay formats are off | AdSense > Ads > By site: searchavo.ai shows "OFF" | **(Owner)** |
| The European regulations message is published | AdSense > Privacy and messaging > European regulations: the message "searchavo.ai consent" is "Published", with "Consent" and "Do not consent" the same size | **(Owner)** |
| Consent mode is on for Google Analytics, when the prod Analytics ID is set | The same page, Settings. Without it Google Analytics stays off for every visitor in Europe | **(Owner)** |
| The ad networks' rules are read | [S8.4.1](https://github.com/silvioincalza/helper2.ai/issues/332) | **(Owner)** |
| The Privacy and Terms text is confirmed by a lawyer, or the owner releases with the points open | The lawyer brief | **(Owner)** |

## 2. The pull request

```bash
deploy/k8s/adsense-go-live.sh --dry-run   # the checks and the change, nothing written
deploy/k8s/adsense-go-live.sh             # the same, then the branch and the pull request
```

The script stops at the first check that is not true. It sets the three ad units in
`deploy/helm/searchavo/values-prod.yaml` on the branch `chore/adsense-prod-ad-units` and opens the pull request. It
never merges and never deploys.

To start with fewer places, empty an ad unit: `UNIT_GUIDE= deploy/k8s/adsense-go-live.sh` leaves the guide answer
without an ad.

**(Owner)** Review and merge the pull request.

## 3. The release, then the switch

The values reach prod with a release: [RUNBOOK-release.md](RUNBOOK-release.md), sections 2 and 3. Nothing shows
yet: `curl -s https://searchavo.ai/api/v1/site | jq .monetisation` still has no ad unit. Staging does not change: it
has no ad unit, and Google serves ads only on the approved domain.

**(Owner)** On `https://admin.searchavo.ai/settings`, row "Google ads": **Set to true**, check that the review says
`false → true`, give the reason, **Confirm change**. The ads start within about 20 seconds.

## 4. Checks on prod

Right after the switch, in a private window. A new ad unit can take up to an hour to fill.

1. **The CSP has a nonce**, and pages are not stored:

   ```bash
   curl -sI https://searchavo.ai/ | grep -i -E 'content-security-policy|cache-control'
   ```

   `script-src 'nonce-…' … 'strict-dynamic'` and `no-store`.
2. **The settings are out:** `curl -s https://searchavo.ai/api/v1/site | jq .monetisation` shows the publisher and the
   three ad units.
3. **Home** shows no Google dialog and loads nothing from Google (the Network tab has no `googlesyndication` and no
   `fundingchoicesmessages` request).
4. **A search** (`/search?q=mcp`): Google's dialog opens once, from Europe. After an answer, one box marked
   "Advertisements" below the results. A filter keeps the same ad. A search that finds nothing shows no box.
5. **A guide question:** no box while the guide works; one box below the picks and the answer; no second box below
   the results.
6. **A resource page:** one box between the sections.
7. **The slot height**, in each of the three places, on a desktop and on a phone: the real ad fits its box, nothing
   is cut and the page does not jump when the ad comes. When it does not fit, change `.h2-adsense` in
   `frontend/src/features/monetisation/sponsor.css` in a pull request.
8. **"Do not consent"** then a new search: the box still shows an ad (not personalised), and Google Analytics sends
   nothing (no `google-analytics.com/g/collect` request).
9. **"Privacy choices"** in the footer opens Google's dialog again.
10. The browser console shows no CSP error.

Two days later: AdSense > Policy centre shows no issue, and Reports shows impressions for the three ad units.

## 5. Going back

- **One place off:** set its value to `""` in `deploy/helm/searchavo/values-prod.yaml`, in a pull request, and
  release. The other places keep their ads.
- **All ads off:** **Set to false** on "Google ads" in the back office (or **Reset to default**, which is `false`). It takes about 20
  seconds and no release. The ad units stay in the values, the publisher stays, so `/ads.txt` and the site check
  tag stay, the strict CSP is back, and our own Analytics bar replaces Google's dialog.
- **The back office is down:** `make -C deploy/k8s ENV=prod rollback` to the revision before, as in
  [RUNBOOK-release.md](RUNBOOK-release.md), section 5. It brings the whole release back, not only the ads.
- **Google reports a policy issue on the guide answer:** empty `adsenseSlotGuide` first, then answer in the Policy
  centre.
