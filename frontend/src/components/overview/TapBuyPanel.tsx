import { useEffect, useRef, useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, fmtUsdGrouped, isNum } from "../../lib/format";
import { useTapAmounts, useTapEnabled, useTapExpanded } from "../../lib/prefs";
import { TAP_COLOR } from "../../lib/stages";
import { IconChevronDown, IconGear, IconLock } from "../Icons";
import { Modal } from "../Modal";

/** how long all tap buttons stay locked after a tap (the server ignores a second tap in this window too) */
const LOCK_MS = 1000;

function cents(price: number | null | undefined): string {
  if (!isNum(price)) return "—";
  const c = price * 100;
  return `${c >= 10 ? c.toFixed(0) : c.toFixed(1).replace(/\.0$/, "")}¢`;
}

function money(amount: number): string {
  return Number.isInteger(amount) ? `$${amount}` : `$${amount.toFixed(2)}`;
}

/** What `amount` pays if the side wins: shares bought at the ask, one dollar each. */
function winAmount(amount: number, ask: number | null | undefined): string {
  if (!isNum(ask) || ask <= 0) return "—";
  const win = amount / ask;
  return win >= 1000 ? `$${Math.round(win).toLocaleString("en-US")}` : `$${win.toFixed(win >= 100 ? 0 : 2).replace(/\.00$/, "")}`;
}

/**
 * One-tap buy: UP and DOWN each have three dollar buttons. One click on a button is one market buy,
 * nothing to confirm. The buttons lock for a second after every tap, so a double click buys once.
 *
 * The panel is OFF by default (gear = settings: ON/OFF switch + the three amounts), so a stray click can
 * never buy, and the header folds it to one line (chevron, remembered). Same behaviour as the Android card.
 * The shares are tagged "One-tap": held to settlement or sold by hand, the strategy never touches them.
 */
export function TapBuyPanel({ className }: { className?: string }) {
  const { snapshot, tapBuy } = useBot();
  const [amounts, setAmounts] = useTapAmounts();
  const [enabled, setEnabled] = useTapEnabled();
  const [expanded, setExpanded] = useTapExpanded();
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [draft, setDraft] = useState<string[]>(["", "", ""]);
  const [draftOn, setDraftOn] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  if (!snapshot) return null;

  const limits = snapshot.tap ?? { min_usdc: 1, max_usdc: 100, cooldown_ms: LOCK_MS };
  const market = snapshot.market;
  const ended = !market || market.remaining_seconds <= 0;
  const stale = typeof snapshot.config.stale_data_max_age_ms === "number" ? snapshot.config.stale_data_max_age_ms : null;

  let blocked: string | null = null;
  if (!enabled) blocked = "Off · switch it on in settings";
  else if (snapshot.bot.kill_switch) blocked = "Kill switch is on";
  else if (snapshot.bot.status !== "RUNNING") blocked = "Start the bot to buy";
  else if (ended) blocked = "Waiting for the next market";

  const sideInfo = (side: Side) => {
    const book = side === "UP" ? snapshot.books.up : snapshot.books.down;
    const ask = book?.best_ask ?? null;
    const old = isNum(book?.age_ms) && stale !== null ? book.age_ms > stale : false;
    return { ask, old };
  };

  const tap = (side: Side, amount: number) => {
    if (!enabled || locked || busy !== null || blocked) return;
    setLocked(true);
    setBusy(`${side}:${amount}`);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setLocked(false), LOCK_MS);
    void tapBuy(side, amount).finally(() => setBusy(null));
  };

  const openSettings = () => {
    setDraft(amounts.map(String));
    setDraftOn(enabled);
    setSettingsOpen(true);
  };
  const draftValid = draft.every((value) => {
    const n = Number(value);
    return value.trim() !== "" && Number.isFinite(n) && n >= limits.min_usdc && n <= limits.max_usdc;
  });
  const save = () => {
    if (!draftValid) return;
    setAmounts(draft.map((value) => Number(value)));
    setEnabled(draftOn);
    setSettingsOpen(false);
  };

  return (
    <section
      className={cx("tap-panel", !enabled && "is-off", !expanded && "is-folded", className)}
      aria-label="One-tap buy"
      style={{ ["--tap-accent" as string]: TAP_COLOR }}
    >
      <header className="tap-head">
        <button
          type="button"
          className="tap-title"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          title={expanded ? "Fold" : "Open"}
        >
          <span className="tap-bolt" aria-hidden="true">⚡</span>
          <span className="tap-name">One-tap buy</span>
        </button>
        <div className="tap-tools">
          {expanded ? (
            <button type="button" className="tap-icon" onClick={openSettings} aria-label="One-tap settings" title="Settings: ON/OFF and amounts">
              <IconGear size={16} />
            </button>
          ) : null}
          <button
            type="button"
            className={cx("tap-icon tap-chevron", expanded && "is-open")}
            onClick={() => setExpanded(!expanded)}
            aria-label={expanded ? "Fold one-tap buy" : "Open one-tap buy"}
          >
            <IconChevronDown size={16} />
          </button>
        </div>
      </header>

      {expanded ? (
        <>
          <div className="tap-grid">
            {(["UP", "DOWN"] as const).map((side) => {
              const { ask, old } = sideInfo(side);
              const noAsk = !isNum(ask);
              return (
                <div key={side} className={cx("tap-row", side === "UP" ? "tap-up" : "tap-down")}>
                  <div className="tap-side" title={old ? "Order book is out of date" : "Best ask"}>
                    <span>{side === "UP" ? "Up" : "Down"}</span>
                    <b className={old ? "tap-old" : undefined}>{noAsk ? "--" : cents(ask)}</b>
                  </div>
                  {amounts.map((amount) => {
                    const key = `${side}:${amount}`;
                    const off = !enabled || Boolean(blocked) || noAsk || old || locked || busy !== null;
                    return (
                      <button
                        key={key}
                        type="button"
                        className={cx("tap-btn", busy === key && "is-busy")}
                        disabled={off}
                        onClick={() => tap(side, amount)}
                        title={`Buy ${money(amount)} of ${side} at the market now`}
                      >
                        <span className="tap-amt">{busy === key ? "…" : money(amount)}</span>
                        <span className="tap-win">win {winAmount(amount, ask)}</span>
                      </button>
                    );
                  })}
                </div>
              );
            })}
          </div>

          <footer className={cx("tap-foot", blocked && "is-warn")}>
            {blocked ? (
              <>
                {!enabled ? <IconLock size={12} /> : null}
                <span className="tap-foot-text">{blocked}</span>
              </>
            ) : (
              <span className="tap-foot-text">One click = one buy · no take profit or stop loss</span>
            )}
            <span className="tap-cash" title="Cash available">{fmtUsdGrouped(snapshot.account.balance)}</span>
          </footer>
        </>
      ) : null}

      <Modal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        eyebrow="One-tap buy"
        title="Settings"
        subtitle="Saved in this browser. Nothing changes until you press Save."
        maxWidth="max-w-md"
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setSettingsOpen(false)}>
              Cancel
            </button>
            <button type="button" className="btn btn-primary" onClick={save} disabled={!draftValid}>
              Save
            </button>
          </>
        }
      >
        <div className="tap-set">
          <div className="tap-set-switch">
            <div>
              <div className="tap-set-title">{draftOn ? "One-tap buy is ON" : "One-tap buy is OFF"}</div>
              <p className="tap-set-sub">
                {draftOn ? "A click on a dollar button buys at once." : "The buttons are locked: a stray click cannot buy."}
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={draftOn}
              aria-label="One-tap buy on or off"
              className={cx("tap-switch", draftOn && "is-on")}
              onClick={() => setDraftOn((on) => !on)}
            >
              <span />
            </button>
          </div>

          <div>
            <div className="tap-set-title">Buy amounts (USDC)</div>
            <div className="tap-set-fields">
              {draft.map((value, index) => (
                <label key={index} className="tap-field">
                  <span>$</span>
                  <input
                    inputMode="decimal"
                    value={value}
                    aria-label={`Amount ${index + 1}`}
                    onChange={(event) => setDraft((prev) => prev.map((v, i) => (i === index ? event.target.value : v)))}
                  />
                </label>
              ))}
            </div>
            <p className="tap-set-sub">
              Each from ${limits.min_usdc} to ${limits.max_usdc}.
            </p>
          </div>
        </div>
      </Modal>
    </section>
  );
}
