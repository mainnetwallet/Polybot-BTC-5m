import { useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, isNum } from "../../lib/format";
import { TAP_STAGE } from "../../lib/stages";
import { Modal } from "../Modal";

/** 0.8 -> "80", 0.855 -> "85.5" (a limit is shown and typed in cents) */
function centsText(price: number): string {
  return String(Math.round(price * 1000) / 10);
}

const SLIDER_MIN = 1;
const SLIDER_MAX = 99;
const SLIDER_THUMB = 22; // px: must match .price-slider input's thumb in index.css
const QUICK_PICKS = [50, 75, 90, 95, 99];

/** where a price sits on the track (px offset is the thumb's half width, so the marker lines up with the thumb) */
function trackPos(cents: number): string {
  const pct = ((Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, cents)) - SLIDER_MIN) / (SLIDER_MAX - SLIDER_MIN)) * 100;
  return `calc(${pct}% + ${(SLIDER_THUMB / 2) * (1 - pct / 50)}px)`;
}

/**
 * Limit price in one drag: a slider over the whole 1-99 range, a - / + pair for single cents, and a few quick picks.
 * Typing in the field above still works (and keeps decimals like 85.5); the slider follows it to the nearest cent.
 * The white mark on the track is the best bid, so you can see how far the limit is from the market.
 */
function PriceSlider({
  value,
  bid,
  fallback,
  onPick,
}: {
  /** the price in the field right now (cents), null when empty or invalid */
  value: number | null;
  /** best bid in cents, null when there is no bid */
  bid: number | null;
  /** where the thumb sits when the field is empty (the best bid, else 50) */
  fallback: number;
  onPick: (cents: number) => void;
}) {
  const clamp = (cents: number) => Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, Math.round(cents)));
  const shown = clamp(value ?? fallback);
  const bidShown = bid === null ? null : clamp(bid);

  return (
    <div className="price-slider">
      <div className="price-slider-step">
        <button type="button" className="btn btn-ghost" aria-label="One cent less" onClick={() => onPick(clamp(shown - 1))}>
          −
        </button>
        <div className="price-slider-value" aria-live="polite">
          {value === null ? <span className="is-empty">{shown}</span> : <span>{Math.round(value * 10) / 10}</span>}
          <small>¢</small>
        </div>
        <button type="button" className="btn btn-ghost" aria-label="One cent more" onClick={() => onPick(clamp(shown + 1))}>
          +
        </button>
      </div>

      <div className="price-slider-track">
        <span className="price-slider-bar" aria-hidden="true" />
        <span className="price-slider-fill" aria-hidden="true" style={{ width: trackPos(shown) }} />
        {bidShown !== null ? (
          <span className="price-slider-bid" aria-hidden="true" style={{ left: trackPos(bidShown) }} />
        ) : null}
        <input
          type="range"
          min={SLIDER_MIN}
          max={SLIDER_MAX}
          step={1}
          value={shown}
          aria-label="Limit price in cents"
          onChange={(event) => onPick(Number(event.target.value))}
        />
      </div>

      <div className="price-slider-ticks" aria-hidden="true">
        {[SLIDER_MIN, 25, 50, 75, SLIDER_MAX]
          .filter((tick) => bidShown === null || Math.abs(tick - bidShown) > 6) /* the bid label needs the room */
          .map((tick) => (
          <span key={tick} style={{ left: trackPos(tick) }}>
            {tick}
          </span>
        ))}
        {bidShown !== null ? (
          <span className="is-bid" style={{ left: trackPos(bidShown) }}>
            bid
          </span>
        ) : null}
      </div>

      <div className="price-slider-quick">
        {QUICK_PICKS.map((cents) => (
          <button
            key={cents}
            type="button"
            className={cx("chip", value === cents && "is-on")}
            onClick={() => onPick(cents)}
          >
            {cents}¢
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * "Limit" button of one side / stage: opens a small dialog to set the price at which that stage's shares are sold.
 *
 * The limit is kept by the bot (in code), not placed on the exchange. So there is never a resting sell order that has
 * to be cancelled before you can sell some other way: "Sell" keeps working at once, and the limit can be set before the
 * shares are even bought (one-tap), changed, or removed at any time. When the best bid reaches the limit the bot sells
 * at the market, never below the limit.
 *
 * It sits next to "Sell" in the positions list (the one-tap panel has no Limit button).
 */
export function LimitButton({
  side,
  stage,
  className,
}: {
  side: Side;
  /** 0-3 = a strategy stage, 9 (TAP_STAGE) = the one-tap shares */
  stage: number;
  className?: string;
}) {
  const { snapshot, setLimit, cancelLimit } = useBot();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);

  if (!snapshot) return null;

  const market = snapshot.market;
  const marketId = market?.market_id ?? null;
  const active = snapshot.limits?.find((l) => l.market_id === marketId && l.side === side && l.stage === stage) ?? null;
  const bid = (side === "UP" ? snapshot.books.up : snapshot.books.down)?.best_bid ?? null;
  const canSet = snapshot.bot.status === "RUNNING" && !snapshot.bot.kill_switch && !!market && market.remaining_seconds > 0;
  const label = stage === TAP_STAGE ? "One-tap" : `Stage ${stage}`;

  const price = Number(draft);
  const valid = draft.trim() !== "" && Number.isFinite(price) && price >= 1 && price <= 99;
  const alreadyThere = valid && isNum(bid) && bid * 100 + 1e-9 >= price;

  const openDialog = () => {
    setDraft(active ? centsText(active.price) : "");
    setOpen(true);
  };

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    const result = await setLimit(side, stage, price / 100);
    setBusy(false);
    if (result.ok) setOpen(false);
  };

  const remove = async () => {
    if (busy) return;
    setBusy(true);
    const result = await cancelLimit(side, stage);
    setBusy(false);
    if (result.ok) setOpen(false);
  };

  const title = active
    ? `Limit sell at ${centsText(active.price)}¢ is set. Click to change or remove it.`
    : stage === TAP_STAGE
      ? `Set a limit sell for ${side} one-tap shares (can be set before you buy)`
      : `Set a limit sell for this ${side} position`;

  return (
    <>
      <button
        type="button"
        onClick={openDialog}
        disabled={!canSet && !active}
        title={title}
        className={cx(
          "btn limit-btn limit-btn-row !px-2.5 !py-1 !text-[12px]",
          active && "is-on",
          className,
        )}
      >
        <span>Limit</span>
        {active ? <b>{centsText(active.price)}¢</b> : null}
      </button>

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        eyebrow="Limit sell"
        title={`${label} ${side}`}
        subtitle={isNum(bid) ? `Best bid now ${centsText(bid)}¢` : "No bid in the order book right now"}
        maxWidth="max-w-md"
        footer={
          <>
            {active ? (
              <button type="button" className="btn btn-danger" onClick={remove} disabled={busy}>
                Remove limit
              </button>
            ) : null}
            <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button type="button" className="btn btn-primary" onClick={save} disabled={!valid || busy || !canSet}>
              {busy ? "Saving…" : active ? "Change limit" : "Set limit"}
            </button>
          </>
        }
      >
        <div className="tap-set">
          <div>
            <div className="tap-set-title">Sell at this price or better (¢)</div>
            <div className="tap-set-fields">
              <label className="tap-field">
                <input
                  autoFocus
                  inputMode="decimal"
                  value={draft}
                  aria-label="Limit price in cents"
                  placeholder="e.g. 85"
                  onChange={(event) => setDraft(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") void save();
                  }}
                />
                <span>¢</span>
              </label>
            </div>
            <PriceSlider
              value={valid ? price : null}
              bid={isNum(bid) ? bid * 100 : null}
              fallback={isNum(bid) ? bid * 100 : 50}
              onPick={(cents) => setDraft(String(cents))}
            />
            <p className="tap-set-sub">
              {draft.trim() !== "" && !valid
                ? "Enter a price from 1 to 99."
                : alreadyThere
                  ? "The bid is already at or above this limit: the shares are sold right away."
                  : "When the best bid reaches this price the bot sells at the market, never below it."}
            </p>
          </div>
          <p className="tap-set-sub">
            The limit is kept by the bot, not placed on the exchange. You can still press Sell at any time, and change or
            remove the limit without cancelling anything.
            {stage === TAP_STAGE ? " It can be set before you buy." : ""} It ends with this market.
          </p>
        </div>
      </Modal>
    </>
  );
}
