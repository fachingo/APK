# Offline market matching and optional independent AI ranking

SQLite full-text indexing (FTS5, FTS4 fallback), indexed token prefixes, a trigram
lexicon and B-tree indexes keep retrieval on the phone. Apache Commons Text1.14.0
provides bounded Levenshtein spelling correction and Jaro-Winkler similarity.
Token overlap and explicit year differences contribute to displayed local scores.
These are proven open-source building blocks; there is no required cloud/vector
service or full-database model upload.

Search supports normalized words, diacritics, prefixes, common OCR spelling errors
and transpositions. If all-word matching and spelling correction find nothing, a
bounded partial-token candidate search ranks up to500 grouped candidates and shows
the strongest page. Refine a broad query for more results. Exact barcode searches
never fuzzy-correct their digits. Currency/year/edition/price/date filters still
apply, and group sale counts/ranges retain their original evidence; fuzzy matching
does not merge films/editions or fabricate sales. An inventory target pre-fills its
known title/year/edition. Approximate result pagination is bounded; normal indexed
catalog/evidence browsing remains paginated.

Local match scores are similarity heuristics, **not calibrated probabilities**.
Review identity, sequels, remakes, edition, year, condition and barcode. You may
review a candidate's title, remove listing clutter, and explicitly save the corrected
local title/year/edition. This clears readiness. Pricing then recalculates from the
existing strict compatible sold evidence and still needs a separate price approval.
Approximate similarity and AI estimates never automatically set an asking price.

Optional AI match ranking is independent from photo-recognition settings. Choose
its provider and model in the Market screen, configure that provider's key in AI
workspace Connections, and approve one ranking request. Ranking sends only the
query, selected inventory identity and up to20 compact candidate identities; no
photographs, full inventory or market database are transmitted. It uses no tools,
accepts only candidate IDs already returned locally and rejects invented IDs,
duplicate IDs, prices and malformed confidence values. AI confidence is explicitly
an uncalibrated model estimate. Failed requests never silently switch providers or
billing methods. OFF and normal local searches make zero model requests.

Each explicit request is stored in AI workspace history with available token/cache
counts and reported or catalog-estimated USD cost; unavailable cost stays unknown.
Search/model changes discard stale displayed AI rankings. Responses are bounded,
no follow-up model rounds run, and there is no idle/background AI observation.
The local/on-device and configured free-provider options remain available; provider
limits and charges depend on the selected connection/model.
