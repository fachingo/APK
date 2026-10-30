# Before / after workflow comparison

Comparison uses the v0.8.4 source and retained screenshots, followed by actual Compose interactions at 420 × 960 dp. Counts describe deliberate actions from the stated starting point, exclude text entry and selection itself, and separate scrolling from taps. Existing capabilities such as bulk operations were preserved, not newly invented.

| Task / starting state | v0.8.4 | v0.9.0 | Verification |
|---|---|---|---|
| Open current batch from Dashboard | 1 tap on Review batches; no named batch shortcut | 1 tap on named current batch | Populated native UI test |
| Start offline pricing with selected copies | 1 action after scrolling through batch controls | 1 action on persistent dock; no control search | Actual selected-ID queued local job; no model requests |
| Draft selected valid copies with saved connection | Refresh connection and find draft button, then submit (at least 2 actions if health not loaded) | 1 Create Drafts action; fresh health and saved location/channels resolved inside action | Native UI + real Room queue, HTTP intercepted |
| Recognize, fill missing metadata, draft same subset | Existing persistent selection; controls in long scrolling column | Same selection, immediate fixed dock for all three actions | Seven-copy test, route return and exact draft ID assertion |
| Open product from batch workspace | Separate all-inventory destination then find record | 1 tap on product / Open item | Populated product editor and photo manager rendered |
| Review batch problems | Scroll job/output controls in same long list | Summary error → Review settings & recovery / More Actions; skipped reasons inline | Existing error text and recovery callbacks retained |
| Change advanced pricing rules | One full statistics form | More Actions → Pricing rules; 3 presets; expand advanced rules | All original statistic/FX/outlier/floor/rounding/protection controls mapped |
| Return from task model settings | Settings → models → Done → batch; selection persisted, local screen scroll not retained across route removal | Same direct Settings → models; Back returns to saved route state and selection | Native selection and recreation tests |
| Browse Shopify from primary navigation | Settings/utility entry then Listings & saved reviews | 1 tap Shopify; Listings is default | Explicit read search only; screen opening sends no remote call |
| Open secondary utility | Settings then find shortcut in long page | More navigation → utility (2 taps); no nesting | Global route menu and existing utility tests |
| Live publication | Existing exact batch review, SUBMIT and approval | Same one-time batch review, SUBMIT and approval | Existing owner and multi-channel approval tests retained |
| Delete bad batch / recover | Local deletion and 7-day Trash already supported | More Actions → Delete batch; More → Trash → Recover | Native deletion/recovery test; no remote delete |

Local deterministic jobs remain durable. A queued draft is reported as queued, not successfully uploaded. Active and Published remain distinct. Offline pricing can leave questionable copies unpriced; the interface does not manufacture matches or prices to fill a card.
