# Demo video script (under 3 minutes)

For the Devpost submission: public on YouTube, under 3:00, shows the app working, explains Nebius + NVIDIA use.
Your own voice. No music unless it is royalty-free (Google Vids' built-in library is fine). Target: **2:35**.

The screen scenes are pre-recorded, full-screen, by `demo-video/record.mjs` (real Chrome window, visible cursor,
human-paced typing and scrolling): `clips/screen/scene-<name>.mp4`. Re-take one scene with
`node record.mjs --screen <name>` (Node 20+; run `--login` first if the Grafana sign-in expired). Each clip is a
little longer than its slot: trim it in the timeline to the time below.

| # | Time | Clip / on screen | What you say |
|---|---|---|---|
| 1 | 0:00–0:12 | You on camera (Google Vids camera recording) or a photo + the helper2.ai logo | "Hi, I'm Silvio Incalza, a software engineer from Zurich. I built Helper2.ai to make finding the right AI tool simple and honest." |
| 2 | 0:12–0:27 | Slide: "New AI tools every day. Chatbots invent the rest." | "There are new AI tools, models and MCP servers every day. Ask a chatbot and it may name a tool that doesn't exist. So I asked: what if an AI search could only tell you what it can prove?" |
| 3 | 0:27–0:45 | `scene-3`: type `mcp postgres`, results, open the source panel ("9 of 9 sources responded") | "One box. One search covers GitHub, Hugging Face, npm, arXiv, the Official MCP Registry and live AI news, all at once. Every result says where it came from and when. If a source fails, the page says so. It never guesses." |
| 4 | 0:45–0:57 | `scene-detail`: click a result's title; its page with the facts table ("Verified", evidence link, retrieved date) | "Open any result and you see every fact with its source, the date it was checked, and the evidence link. If there's no AI summary yet, it says so." |
| 5 | 0:57–1:32 | `scene-4`: type the question, **Ask the guide**, the four steps, the pick with its checked quote, the model names | "Now I describe what I need in my own words and ask the guide. This is where NVIDIA Nemotron on Nebius Token Factory comes in. Nemotron Nano turns my words, in any language, into a search plan. Our federated search finds real candidates. Then Nemotron 3 Super picks the best, and for each one it must quote the project's own text. Code checks that quote word for word. If the model invents a tool or a quote, it never reaches the page." |
| 6 | 1:32–1:52 | `scene-5`: **Guide me** on playwright-mcp, Claude Code, the snippet and the steps | "Finding a tool is half the job. Guide me shows how to start it: a config snippet for nineteen AI clients, from the Official MCP Registry or the project's README. Secrets are never filled in, and every command is checked against the README." |
| 7 | 1:52–2:10 | `scene-7`: GitHub repo page (About, Apache-2.0), README, the ADR list | "Helper2.ai is open source under Apache-2.0, live at helper2.ai, with no account needed. It's built with Spring Boot, Spring AI and React, with more than five hundred backend tests. It's funded by donations and partnerships, never by paid rankings." |
| 8 | 2:10–2:25 | `scene-grafana`: the live prod Grafana dashboard, overview, then the AI guide row (model call p95 by Nemotron model) | "It runs on Kubernetes, fully monitored. This is the live production dashboard: every search, every source, and every Nemotron call on Nebius, with its latency, tokens and daily budget." |
| 9 | 2:25–2:35 | End card: `helper2.ai` · `github.com/silvioincalza/helper2.ai` · "Built with NVIDIA Nemotron on Nebius Token Factory" | "Helper2.ai: the AI ecosystem metasearch engine that only tells you what it can prove. Thanks for watching." |

Spare if there is time left: `scene-6` (Next events on the home page), "It also shows upcoming AI events, only when a
source states their dates." Without it the video is 2:35.

## Google Vids steps

1. Open https://vids.google.com → **Blank video**.
2. Scene 1: **Record → Camera** for your intro (or a photo plus **Record → Audio**).
3. Scenes 3–8: **Insert → Upload** the clips from `demo-video/clips/screen/` in the table's order; trim each to its
   slot; add your voice per scene with **Record → Audio**. Re-record a scene's voice, not the whole video.
4. Scenes 2 and 9: text slides (**Insert → Text**); add your voice with **Record → Audio**.
5. Check the total length in the timeline: **under 3:00**.
6. **File → Download → MP4** (needs disk space; or upload from Google Drive).
7. YouTube Studio → **Create → Upload video** → visibility **Public** → copy the link.
8. Paste the link into the Devpost form and into the README ("Video: _to be added_").

Upload a day early: the deadline is **Fri 30 Oct, 10:00 AM PT**.
