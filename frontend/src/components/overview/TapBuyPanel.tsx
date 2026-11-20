import { useEffect, useRef, useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, fmtUsdGrouped, isNum } from "../../lib/format";
import { useTapAmounts } from "../../lib/prefs";
import { TAP_COLOR } from "../../lib/stages";

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
 * The shares are tagged "One-tap": held to settlement or sold by hand, the strategy never touches them.
 */
export function TapBuyPanel({ className }: { className?: string }) {
  const { snapshot, tapBuy } = useBot();
  const [amounts, setAmounts] = useTapAmounts();
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string[]>(["", "", ""]);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  if (!snapshot) return null;

  const limits = snapshot.tap ?? { min_usdc: 1, max_usdc: 100, cooldown_ms: LOCK_MS };
  const live = snapshot.bot.mode === "live";
  const market = snapshot.market;
  const ended = !market || market.remaining_seconds <= 0;
  const stale = typeof snapshot.config.stale_data_max_age_ms === "number" ? snapshot.config.stale_data_max_age_ms : null;

  let blocked: string | null = null;
  if (snapshot.bot.kill_switch) blocked = "Kill switch is on";
  else if (snapshot.bot.status !== "RUNNING") blocked = "Start the bot to buy";
  else if (ended) blocked = "Waiting for the next market";

  const sideInfo = (side: Side) => {
    const book = side === "UP" ? snapshot.books.up : snapshot.books.down;
    const ask = book?.best_ask ?? null;
    const old = isNum(book?.age_ms) && stale !== null ? book.age_ms > stale : false;
    return { ask, old };
  };

  const tap = (side: Side, amount: number) => {
    if (locked || busy !== null || blocked) return;
    setLocked(true);
    setBusy(`${side}:${amount}`);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setLocked(false), LOCK_MS);
    void tapBuy(side, amount).finally(() => setBusy(null));
  };

  const beginEdit = () => {
    setDraft(amounts.map(String));
    setEditing(true);
  };
  const saveEdit = () => {
    const parsed = draft.map((value) => Number(value));
    const ok = parsed.every((value) => Number.isFinite(value) && value >= limits.min_usdc && value <= limits.max_usdc);
    if (!ok) return;
    setAmounts(parsed);
    setEditing(false);
  };
  const draftValid = draft.every((value) => {
    const n = Number(value);
    return value.trim() !== "" && Number.isFinite(n) && n >= limits.min_usdc && n <= limits.max_usdc;
  });

  return (
    <section className={cx("tap-panel", className)} aria-label="One-tap buy" style={{ ["--tap-accent" as string]: TAP_COLOR }}>
      <header className="tap-head">
        <div className="tap-title">
          <span className="tap-bolt" aria-hidden="true">⚡</span>
          <span className="tap-name">One-tap buy</span>
          {live ? <span className="tap-live">LIVE</span> : <span className="tap-paper">PAPER</span>}
        </div>
        <div className="tap-sub">
          <span className="tap-cash" title="Cash available">{fmtUsdGrouped(snapshot.account.balance)}</span>
          <button type="button" className="tap-edit" onClick={editing ? () => setEditing(false) : beginEdit}>
            {editing ? "Cancel" : "Edit"}
          </button>
        </div>
      </header>

      {editing ? (
        <div className="tap-editor">
          <div className="tap-editor-row">
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
          <p className="tap-hint">
            ${limits.min_usdc} to ${limits.max_usdc} each. Saved in this browser.
          </p>
          <button type="button" className="btn btn-primary tap-save" onClick={saveEdit} disabled={!draftValid}>
            Save amounts
          </button>
        </div>
      ) : (
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
                  const off = Boolean(blocked) || noAsk || old || locked || busy !== null;
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
      )}

      <p className={cx("tap-note", blocked && "is-warn")}>
        {blocked ?? "One click = one buy · no take profit or stop loss"}
      </p>
    </section>
  );
}
