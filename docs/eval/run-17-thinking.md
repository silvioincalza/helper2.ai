# Run 17 — where the time goes, why top picks vary, thinking on vs off

Measured 27 Oct 2026, 02:28–03:05 UTC, against `https://helper2.ai` (image of `abae7df`) and Token Factory directly. Scripts were one-off; this file keeps the numbers.

## 1. Prod, two runs of the 20 queries (suffixes " ?n1" and " ?n2" to bypass the answer cache)

Backend log (`guide timings`), typical question: plan (Nemotron 3 Nano) 4.7–5.2 s, search 1.8–2.4 s, choose (Nemotron 3 Super) 10.4–11.1 s.

| id | latency run 1 | latency run 2 | planner query run 1 | planner query run 2 | same 24 search results | top pick run 1 | top pick run 2 |
|---|---|---|---|---|---|---|---|
| b01 | 21381 ms | 25925 ms | MCP server postgres | MCP server postgres | yes | erikdarlingdata/PerformanceMonitor | erikdarlingdata/PerformanceMonitor |
| b02 | 15715 ms | 19864 ms | MCP server github issues | MCP server GitHub issues | yes | Lilli-Ai/github-mcp-server | Lilli-Ai/github-mcp-server |
| b03 | 28978 ms | 72613 ms | text embeddings model | small text embeddings model | no | – | RavenGan/FinetuneEmbed |
| b04 | 26944 ms | 24990 ms | retrieval augmented generation PDF library | PDF retrieval augmented generation library | no | antoninomariarizzo/rag | antoninomariarizzo/rag |
| b05 | 27631 ms | 33246 ms | speech to text model | speech to text model | yes | fractalego/personal-speech-to-text-model | PaddlePaddle/PaddleSpeech |
| b06 | 20131 ms | 20657 ms | AI agent orchestration framework | AI agent orchestration framework | yes | @framers/agentos | @framers/agentos |
| b07 | 21904 ms | 30675 ms | stream LLM responses React | LLM response streaming React | no | Ziad-777/react-stream-chat | Ziad-777/react-stream-chat |
| b08 | 11791 ms | 14943 ms | MCP server slack | MCP server slack | yes | @mindstone/mcp-server-slack | @mindstone/mcp-server-slack |
| b09 | 24545 ms | 17269 ms | LLM output evaluation tool | LLM output evaluation tool | yes | Alqemist-labs/ruby_llm-tribunal | Alqemist-labs/ruby_llm-tribunal |
| b10 | 14797 ms | 26114 ms | retrieval-augmented generation | retrieval-augmented generation hallucinations | no | FAIR-RAG: Faithful Adaptive Iterative Refinement for Retrieval-Augmented Generation | FilterRAG: Zero-Shot Informed Retrieval-Augmented Generation to Mitigate Hallucinations in VQA |
| b11 | 23229 ms | 21506 ms | image segmentation model | image segmentation model | yes | debasishray16/satellite_image_segmentation_ResNet_Models | debasishray16/satellite_image_segmentation_ResNet_Models |
| b12 | 21436 ms | 18699 ms | MCP server headless browser | MCP server headless browser | yes | random-robbie/mcp-web-browser | jae-jae/fetcher-mcp |
| b13 | 11809 ms | 12045 ms | JSON schema extraction library | JSON schema extraction library | yes | Edgaras0x4E/LLM-JSON-Cleaner | Edgaras0x4E/LLM-JSON-Cleaner |
| b14 | 13945 ms | 16772 ms | MCP server google drive | MCP server google drive | yes | @ibarcarty/mcp-server-google-drive | @ibarcarty/mcp-server-google-drive |
| u01 | 14297 ms | 37384 ms | multilingual document translation | document translation | no | – | project-anuvaad/anuvaad |
| u02 | 38407 ms | 14340 ms | meeting notes summarizer | meeting notes summarizer | yes | mastra-ai/template-meeting-notes | generative-ai-on-aws/summarize-me |
| u03 | 15823 ms | 20743 ms | podcast transcription model | podcast transcription | no | ironsupr/Podcast-Transcription-using-Wisper | wendy7756/podcast-transcriber |
| u04 | 11538 ms | 13705 ms | customer complaint response template | polite customer complaint reply | no | – | AjaySreekumar47/customer-support-nlp-llm-pipeline |
| n01 | 39970 ms | 12572 ms | NVIDIA Nemotron models news | NVIDIA Nemotron models news | yes | NVIDIA Releases Nemotron 3.5 Bolt E4B 4GB: An Open Small Language Model for Local Voice Agents, Game Characters and Robots - MarkTechPost | NVIDIA Releases Nemotron 3.5 Bolt E4B 4GB: An Open Small Language Model for Local Voice Agents, Game Characters and Robots - MarkTechPost |
| x01 | 16818 ms | 17980 ms | quantum blockchain toaster poem generator | quantum blockchain toaster poem writer | no | – | – |

`same 24 search results` compares `GET /api/v1/search?q=<planner query>&size=24` at each run.

## 2. Same 24 candidates, Nemotron 3 Super choose with thinking on (T) vs off (N)

Candidates: `GET /api/v1/search` with the run-1 planner query (tool types only, except b10 and n01). Same choose prompt and JSON schema as production.

| id | T time | T tokens | T picks | N time | N tokens | N picks |
|---|---|---|---|---|---|---|
| b01 | 21.2 s | 1772 | erikdarlingdata/PerformanceMonitor; crbnos/carbon | 1.3 s | 97 | antonorlov/mcp-postgres-server |
| b02 | 13.9 s | 2530 | Lilli-Ai/github-mcp-server; zaccomode/github-issues-mcp; cyanheads/github-mcp-server | 0.8 s | 156 | Lilli-Ai/github-mcp-server; cyanheads/github-mcp-server |
| b03 | 10.4 s | 1297 | – | 0.8 s | 18 | – |
| b04 | 7.5 s | 1417 | pvmodayil/ragyphi; antoninomariarizzo/rag; eodeluga/rag-time | 2.4 s | 277 | pvmodayil/ragyphi; antoninomariarizzo/rag |
| b05 | 7.0 s | 984 | – | 0.7 s | 18 | – |
| b06 | 9.4 s | 2106 | realyinchen/AgentHub; @framers/agentos; AgenticGoKit/AgenticGoKit | 1.7 s | 294 | microsoft/agent-framework; crewAIInc/crewAI; network-ai |
| b07 | 9.8 s | 2140 | Ziad-777/react-stream-chat; Yashkanekar/laminar-ui; pooyagolchian/use-local-llm | 1.3 s | 135 | Ziad-777/react-stream-chat; streamdown |
| b08 | 6.9 s | 1142 | @mindstone/mcp-server-slack; korotovsky/slack-mcp-server; slack-mcp-user | 1.3 s | 158 | @mindstone/mcp-server-slack; korotovsky/slack-mcp-server |
| b09 | 10.0 s | 1971 | Alqemist-labs/ruby_llm-tribunal; f5devcentral/prompt-evaluator; openevals | 3.5 s | 222 | Alqemist-labs/ruby_llm-tribunal; pd-a11y/LLM-output-evaluation-platform; Tri2301/llm-output-evaluator |
| b10 | 8.7 s | 829 | FAIR-RAG: Faithful Adaptive Iterative Refinement for Retrieval-Augmented Generation | 0.6 s | 18 | – |
| b11 | 14.4 s | 3442 | PaddlePaddle/PaddleSeg; JuliaWolleb/Diffusion-based-Segmentation; ZFTurbo/ZF_UNET_224_Pretrained_Model | 1.0 s | 148 | PaddlePaddle/PaddleSeg |
| b12 | 11.4 s | 2501 | random-robbie/mcp-web-browser; jae-jae/fetcher-mcp; ahmedrowaihi/mcp-server-playwright-headless | 1.3 s | 223 | random-robbie/mcp-web-browser; jae-jae/fetcher-mcp; pranavms13/web-search-mcp |
| b13 | 5.0 s | 788 | Edgaras0x4E/LLM-JSON-Cleaner; ashwinpaulallen/llm-schema-validator | 1.1 s | 146 | Edgaras0x4E/LLM-JSON-Cleaner; ashwinpaulallen/llm-schema-validator |
| b14 | 16.3 s | 3472 | @botrun/mcp-google-drive; isaacphi/mcp-gdrive; domdomegg/google-drive-mcp | 1.6 s | 254 | piotr-agier/google-drive-mcp; mcp-google-drive; @botrun/mcp-google-drive |
| u01 | 5.1 s | 668 | – | 0.6 s | 18 | – |
| u02 | 13.6 s | 3267 | mastra-ai/template-meeting-notes; generative-ai-on-aws/summarize-me; pi-weave | 1.1 s | 157 | mastra-ai/template-meeting-notes; generative-ai-on-aws/summarize-me |
| u03 | 6.6 s | 1603 | ironsupr/Podcast-Transcription-using-Wisper; wendy7756/podcast-transcriber; VanshikaKumawat/podcast-transcription-segmentation | 1.5 s | 164 | ironsupr/Podcast-Transcription-using-Wisper; wendy7756/podcast-transcriber |
| u04 | 8.0 s | 1709 | ilyautov/humanizer-ru | 0.7 s | 18 | – |
| n01 | 7.1 s | 1626 | NVIDIA Releases Nemotron 3.5 Bolt E4B 4GB: An Open Small Language Model for Local Voice Agents, Game Characters and Robots - MarkTechPost; Nvidia Releases Open Nemotron 3.5 Super VL Model; Nvidia's Nemotron 3.5 Ships at 30B, Eyes 1T Params [2026] | 2.1 s | 287 | NVIDIA Releases Nemotron 3.5 Bolt E4B 4GB: An Open Small Language Model for Local Voice Agents, Game Characters and Robots - MarkTechPost; Nvidia Releases Open Nemotron 3.5 Super VL Model |
| x01 | 2.2 s | 129 | – | 0.8 s | 18 | – |

Judged by hand: N better for b01 (T chose a SQL Server monitor and an ERP), b06 (T chose small repos, N Microsoft Agent Framework and CrewAI), u04 (T chose an unrelated Russian "humanizer"); T better for b10 (N found no paper). Same or equivalent top pick elsewhere.

Repeat test, same input twice: with thinking the RAG-over-PDF question gave two different pick lists; without thinking every repeat was identical (4 queries × 2).

## 3. Planner with thinking off, 3 repeats per query

Identical query text in 3 of 3 repeats for most queries; small wording changes remained for b03, b06, b10 and b13 ("small open model text embeddings" vs "small open text embedding model"). With thinking on, prod gave a different query for 6 of 16. Latency 0.5 s vs 2.5 s, 47 vs 526 completion tokens (b01).

## 4. Reason check (Nemotron 3 Super, thinking off)

4 queries, 9 real reasons from the chooser, each also sent with an added false claim ("It is free and supports Italian.", "It works fully offline and has 10k GitHub stars.", "It is the official and most popular option, MIT licensed."). 9 of 9 real reasons supported, 9 of 9 altered ones rejected, 0.9–1.1 s per call.
