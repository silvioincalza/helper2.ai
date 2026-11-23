# Glossary

| Term              | Definition | Avoid |
|-------------------|------------|-------|
| Resource          | One AI tool, model, MCP server, agent, agent skill, prompt, library, dataset, paper, course, event or news item that helper2.ai knows about. | |
| Catalog           | The resources helper2.ai has stored, with their facts, evidence and relationships. | |
| Source            | An external service helper2.ai reads resources from, such as GitHub, Hugging Face or arXiv; the set is disclosed on the Sources page. | Connector |
| Live Source       | A source that is asked at search time instead of being read into the catalog beforehand. | |
| Search            | One query run across every enabled source and the catalog at once, merged into one result list. | Lookup |
| Guide             | The AI that picks at most a few catalog resources for a need described in words, each with a checked quote and a reason. | Chatbot |
| Pick              | A resource the guide recommends, with its quote and its reason. | Recommendation |
| Quote             | An exact excerpt of a source's text that supports a pick or a fact, checked word for word by code. | Snippet |
| Evidence          | The stored quotes and pages a fact of a resource rests on. | Proof |
| Relationship      | A stated link between two resources, or between a resource and a known target, such as "works with" or "made by". | Integration |
| Known Target      | A vendor, service or tool a relationship may name although the catalog has no resource for it. | |
| Enrichment        | The step that adds a description, fields and relationships to a resource from its sources, each backed by evidence. | |
| Scout             | The background process that collects new resources from the sources and enriches them. | Bot |
| Candidate         | A resource, relationship or known target the Scout found that waits for a decision before it is published. | |
| Review Item       | One open question for a person in the back office: a candidate, a user report, a conflicting fact or a validation issue. | Ticket, Task |
| User Report       | A visitor's report that a resource holds wrong or outdated information. | Complaint |
| Setup Guide       | The steps to install, configure and connect a resource to AI tools, written from its own documentation. | Tutorial |
| Hub Page          | The public page of one resource type that search engines may index. | Landing page |
| Shadow Library    | A site that offers pirated books or papers; search never shows one. | |
| Discovery Host    | A host the Scout may read when it looks for new resources. | |
| List Seed         | A GitHub repository or web page that lists AI resources and that the Scout reads: an administrator added it, or discovery found the page on a directory host whose lists are read. | |
| Home Example      | An example search the home page offers, checked regularly for good results. | |
| Runtime Setting   | A spending limit or switch an administrator may change without a deploy. | Feature flag |
| Retention Period  | How long one kind of data is kept before it is deleted. | |
| Visitor           | Anyone on the public site, signed in or not. | Guest, Anonymous user |
| Signed-in User    | A visitor who signed in with GitHub. | Member, Customer |
| Administrator     | A person with the administrator role on the back office. | Moderator |
| Back Office       | The administration application, reachable only over the VPN. | Admin panel, Dashboard |
| Saved Resource    | A resource a visitor kept to come back to, in the browser or in the account. | Favorite |
