# Section 3 — Research and verification: Arena Agent Mode

**Research date:** 2026-11-10 (all "accessed" dates below are 2026-11-10, UTC)
**Method:** primary Arena documentation opened and read in full via HTTP fetch (not just
search snippets). Search was used only to *locate* pages. Quotes are verbatim from the
fetched page text.

Legend for confidence labels used in this file and in `report.json`:

| Label | Meaning |
|---|---|
| **VERIFIED** | Stated explicitly in primary Arena documentation that I fetched and read. |
| **PARTIAL** | Documented in part; some component is not published. |
| **INTERPRETATION** | My inference from verified text — not a direct quote. |
| **NOT DOCUMENTED** | Not established by any source I could access. No answer is invented. |

---

## Sources inspected

| ID | Source | URL | Published | Last updated |
|---|---|---|---|---|
| S1 | Arena Help Center — "How to use Agent Mode on Arena" | https://help.arena.ai/articles/5432423882-how-to-use-agent-mode | not shown | rendered as "Last updated 1 month ago" (≈2026-10-08; search index date 2026-10-08) |
| S2 | Arena blog — "Agent Arena: Causal Evaluation of Agents in the Real World" | https://arena.ai/blog/agent-arena-methodology | 2026-06-04 | 2026-10-15 |
| S3 | Arena resource — "Agent Arena Signal Definitions" | https://arena.ai/resources/agent-arena-signals | not shown | 2026-10-12 |
| S4 | Agent Arena leaderboard, Capabilities / Work board | https://arena.ai/leaderboard/agent/work | live | snapshot data dated 2026-11-08 (1,605,949 sessions, 57 models) |
| S5 | Arena blog — "Empowering Users to Get More Done With Agent Mode" | https://arena.ai/blog/agent-mode | 2026-06-04 | 2026-06-05 |
| S6 | Arena X/Twitter announcement (secondary channel, Arena-authored) | https://x.com/arena/status/2064021507681276234 | 2026-06-08 | — |
| S7 | Third-party explainer — esospace, "What Arena's Agent Leaderboard Measures, and What It Misses" | https://eseospace.com/blog/what-is-arena-agent-leaderboard/ | 2026-11-02 | — |

S6 and S7 are **secondary** for this report: S6 is Arena-authored but a social post, S7 is
not Arena. Neither is used to establish a fact that S1–S4 state.

S2 itself points to the newer definition page: *"**10/1/26 methodology update**: click
through for the latest signals and updated definitions"*, linking to S3. That makes S3 the
authoritative definition source for the signals, ahead of the June 2026 prose in S2.

The page https://arena.ai/agent-mode was also requested; it returns a JavaScript application
shell with no article text, so it contributed nothing and is not cited.

---

## Q1. How is the model (orchestrator) selected for Agent Mode?

**Confidence: PARTIAL**

Verified facts:

- Selection is by **random assignment per session**, not user choice:
  > "Arena's Agent Mode routes every real session to a randomly chosen model and watches how
  > that model actually does the work." — S1
- The session is powered by one dedicated orchestrator:
  > "When you start a new chat in Agent Mode, your workflow is powered by a new dedicated
  > orchestrator model." — S1
- All models shown on the Agent Mode leaderboard are available in Agent Mode:
  > "All models listed on the Agent Mode leaderboard are available on Agent Arena." — S1
- Randomisation is the experimental mechanism behind the ranking:
  > "by randomizing the component selections, we create a multi-intervention randomized
  > controlled trial in which we can aggregate measurements to estimate causal treatment
  > effects." — S2
- Only the orchestrator is randomised *today*:
  > "The current leaderboards evaluate orchestrators and no other components, so in the
  > production setting we currently have K = 1." — S2

**Interpretation:** the orchestrator (the model that chooses which tools to call) is sampled
from the pool of models on the Agent leaderboard; the user does not pick it, and the choice
feeds a randomised controlled trial used for ranking.

**Not documented:** the sampling distribution / probabilities, whether routing depends on
task content, modality, plan, region or user tier, how the "response is likely to fail"
condition for a mid-session switch is computed (see Q3), and whether sub-agent, image or
other component models are randomised (S2 says K = 1 today, i.e. orchestrator only).

---

## Q2. Is the underlying model identity necessarily disclosed to users?

**Confidence: VERIFIED (as of the current S1 revision)**

- > "Unlike Battle Mode, Agent Mode does not currently reveal the orchestrator model after
  > feedback is submitted. This design choice helps keep the focus on completing tasks and
  > maintaining workflow continuity. This enables us to build fair evaluations for the
  > community." — S1

So: **no, identity is not necessarily disclosed**, and by design it is withheld in Agent Mode
even after feedback, in contrast with Battle Mode where the model pair is revealed after
voting. The word "currently" means this is a policy that could change; nothing on S1–S5
promises permanent non-disclosure. No source states that identity is *never* disclosed
(e.g. via enterprise configuration or support tooling) — that is **NOT DOCUMENTED**.

**Interpretation:** users cannot self-report "which model did this" reliably; any claim about
the identity of the model in a given session must be treated as unverified.

---

## Q3. Can the underlying model change during an active session?

**Confidence: VERIFIED (existence of the exception); PARTIAL (the rule that triggers it)**

Both statements appear in the same FAQ entry in S1:

- Default: > "When you start a new Agent Mode chat session, a model is selected and will
  remain the same throughout the conversation."
- Exception: > "To improve reliability, if a response is likely to fail and leave the session
  in a broken state, the system may automatically switch to a different model so the session
  can continue. Because of this, you may occasionally notice the model change during an Agent
  Mode chat session."

So **yes, in principle** — normally fixed, but an automatic reliability-driven switch is
documented as possible. The trigger ("likely to fail and leave the session in a broken
state") is deliberately vague; the detector, thresholds, frequency, and whether the switch
can happen more than once are **NOT DOCUMENTED**.

**Interpretation:** a single long session is *mostly* single-model, so per-session
attribution is reasonable but not guaranteed. Any statement of the form "model X performed
every action in this session" is therefore unprovable from the user side.

---

## Q4. What are the current five agent leaderboard signals?

**Confidence: VERIFIED (names and definitions, S3 dated 2026-10-12)**

1. **Confirmed Success** — share of tasks the user explicitly confirms as complete.
2. **Praise vs Complaint** — per task, whether explicit praise outnumbers explicit complaint
   (ties / no sentiment = not scored).
3. **Steerability** — effort a user's correction costs: none / low (next turn fine) / high
   (correction has to be repeated).
4. **Bash Reliability** *(previously "Bash Recovery")* — bash failures per non-exempt call;
   environment-caused failures are exempt.
5. **Tool Hallucination** — rate of tool calls that reference a tool that does not exist;
   lower is better.

Quoted definitions (S3):

> "A call to a real, available tool is clean. A call that invents a tool name, uses a
> signature that doesn't exist, or references a capability the agent doesn't actually have
> counts as a tool hallucination."

> "A model's score is based on the number of bash failures per call, across all non-exempt
> calls. More failures count negatively against the model's Net Improvement score for Bash
> Reliability."

S1's FAQ lists the same five, but still under the older label "Bash Recovery":
> "confirmed success, praise vs complaint, steerability, bash recovery, and tool hallucination."

S4's leaderboard columns match S3's current naming exactly: *Net Improvement, Confirmed
Success, Praise vs Complaint, Steerability, Bash Reliability, Tool Hallucination*.

**Interpretation:** the headline "Net Improvement" is a weighted average of per-signal
treatment effects relative to the average model (S1, S2), not a raw success rate. The exact
weights are contradictory across sources — see Conflict C2.

---

## Q5. How does Arena distinguish a tool hallucination from a legitimate tool call?

**Confidence: PARTIAL**

Verified: the criterion is **whether the tool exists in the agent's available toolset and
whether the invocation is well-formed**:

> "We score every tool call the agent makes by whether the tool it invoked actually exists in
> its available toolset." — S3
> "Calling a real function with valid arguments is clean; calling a tool that was never
> registered, or claiming a file-editing capability that isn't there, counts as a
> hallucination." — S3

Malformed-but-recognisable cases count against the model (S1):
> "an invented tool name, malformed junk, or stray reasoning text leaking into the tool field."

The environment is also a source of ground truth (S2):
> "the agent … is interacting with an environment that talks back continuously: shell exit
> codes, tool errors, the absence of a tool it tried to call."

S3 adds that base rates are low and the signal is most informative at the margin.

**Not documented:** the classifier itself. Whether it is a registry/schema lookup, a validator
over the harness tool manifest, an LLM judge, or a hybrid; how partial JSON, retried calls,
or a correctly-named tool with invalid arguments are bucketed; and whether repeat attempts at
one invented tool count once or per call. I did not find any public source describing that
implementation, so I do not assert one.

**Interpretation:** the distinction is *availability + validity at call time*, evaluated
against that session's tool manifest, not against a universal list of tools. A tool that
exists elsewhere but is unavailable in the current session would therefore count as
hallucinated.

---

## Q6. What does Arena mean by "confirmed success"?

**Confidence: VERIFIED**

> "We prompt the user with a pop-up that asks: 'Did this complete your task?' If the user
> clicks 'yes', the task counts towards a confirmed success. If the user clicks 'no', it does
> not count as confirmed. There is no partial credit for work that looks right but that was
> never actually validated by the user." — S3

> "A task where the user says 'perfect, thanks' or clicks accept does not count as confirmed …
> A task where the user pushes back, walks away mid-session, or never responds after the last
> turn also does not count, even if the output was technically fine." — S3

> "Confirmed success — the user marks a task as a success or failure using the Arena UI …
> we use the final approval or disapproval of a given task's trajectory … (There can be more
> than one task per session.)" — S2

So "confirmed success" is **not** "the agent said it finished", **not** "the tests passed",
and **not** general satisfaction. It is only the explicit "yes" answer to the completion
pop-up for that task. Implication: it is a conservative, likely under-counting signal
(third-party S7 makes the same observation, which is consistent but not authoritative).

---

## Conflicts and outdated documentation

**C1 — "Bash Recovery" vs "Bash Reliability" (outdated naming + changed definition).**
S2 (published Jun 4, last updated Oct 15 but retaining June prose and its per-signal table)
defines *Bash recovery* as "turns taken to recover from a bash error", with a penalty if the
agent gives up. S3 (Oct 12) renames it *Bash Reliability* and redefines it as *failures per
non-exempt call*, with environment failures exempt. S1 and S6 still print "Bash Recovery".
**Assessment:** S3 wins — S2 explicitly redirects readers to S3 for "the latest signals and
updated definitions". Treat the S2 table, the S1 FAQ wording and the June X post as stale on
this point. The underlying intent (punish model-caused shell failures, exempt environment
failures) is stable across versions.

**C2 — Signal weighting is contradictory.** S1 says the headline is an equal-weighted
average: *"The headline rank is a weighted average … so every signal gets one vote. Today,
the average is equally weighted, but we may change this."* The leaderboard page's FAQ text
as returned by the search index (S4, snapshot 2026-11-08) instead says *"Bash Reliability
carries half the weight of each other signal"*. The visible HTML I fetched for S4 renders the
FAQ questions but not the answers, so I could not confirm the half-weight sentence on the
rendered page itself.
**Assessment:** unresolved. Most likely explanation is documentation drift (weighting changed
or is being tested while S1's help article lagged). I treat weights as **uncertain**, and note
that any score computed from per-signal columns using equal weights may not reproduce the
published aggregate.

**C3 — Which leaderboard is "the" leaderboard.** `/leaderboard/agent` and
`/leaderboard/agent/work` are separate boards with different populations and different
leaders at different dates (a 2026-10-04 snapshot named Claude Fable 5.1 as the confirmed-
success leader; the 2026-11-08 Work board names Claude Sonnet 5.5). S2 also states rankings
are of orchestrators only and that other harness components are "coming soon".
**Assessment:** quote leaderboard figures only with their board name and snapshot date;
do not compare numbers across boards or dates.

**C4 — "The model stays the same" vs "the model may change".** Both sentences sit in the same
S1 FAQ answer (Q3 above). This is not a true contradiction, but it is easy to misquote;
the accurate summary is *fixed by default, auto-switch on anticipated failure*.

**C5 — Secondary sources drifting further.** S7 (2026-11-02) summarises the signals correctly
but adds unverifiable structural claims (for example that a separate reliability board
averages positions across four Arena signals). S6 (2026-06-08) lists signals using the
pre-October names. Neither is Arena documentation; both are excluded from factual claims here.

---

## What I could not verify (disclosed, not papered over)

- Exact model-sampling probabilities and any routing heuristics (Q1).
- The implementation of tool-hallucination detection and of the "likely to fail" model-switch
  detector (Q3, Q5).
- Whether model identity is ever disclosed through non-public channels (Q2).
- Exact publication/update dates for S1: the page renders a relative string ("1 month ago");
  the 2026-10-08 date comes from the search index, not from the page itself.
- Current signal weights (C2) and the exact aggregate formula.
- Any internal definition of "task" boundaries beyond "there can be more than one task per
  session" (S2).
