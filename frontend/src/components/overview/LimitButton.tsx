import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, isNum } from "../../lib/format";
import { TAP_STAGE } from "../../lib/stages";
import { Modal } from "../Modal";

/** 0.8 -> "80", 0.855 -> "85.5" (a limit is shown and typed in cents) */
function centsText(price: number): string {
  return String(Math.round(price * 1000) / 10);
}

const RULER_MIN = 1;
const RULER_MAX = 99;
const RULER_STEP = 44; // px per cent: must match .price-ruler-item in index.css

/**
 * One line of cents (1-99) that scrolls left to right: whatever sits under the centre marker is the price.
 * Drag it, swipe it, use the mouse wheel, or tap a number. Typing in the field moves the line to that price.
 * A price typed with decimals (85.5) is kept as typed; the line only follows it to the nearest cent.
 */
function PriceRuler({
  value,
  fallback,
  onPick,
}: {
  /** the price in the field right now (cents), null when empty or invalid */
  value: number | null;
  /** where the line starts when the field is empty (the best bid, else 50) */
  fallback: number;
  onPick: (cents: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const byUser = useRef(false); // the scroll comes from the person, not from the field following a typed price
  const idle = useRef<number | undefined>(undefined);
  const drag = useRef<{ x: number; left: number; moved: boolean } | null>(null);
  const shown = Math.min(RULER_MAX, Math.max(RULER_MIN, Math.round(value ?? fallback)));
  const target = (shown - RULER_MIN) * RULER_STEP;

  // first paint: start on the current price without touching the field
  useLayoutEffect(() => {
    if (ref.current) ref.current.scrollLeft = target;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // the field changed (typing, tap on a number): the line follows, unless the person is scrolling it right now
  useEffect(() => {
    const el = ref.current;
    if (!el || byUser.current) return;
    if (Math.abs(el.scrollLeft - target) > 1) el.scrollTo({ left: target, behavior: "smooth" });
  }, [target]);

  useEffect(() => () => window.clearTimeout(idle.current), []);

  const touch = () => {
    byUser.current = true;
    window.clearTimeout(idle.current);
    idle.current = window.setTimeout(() => {
      byUser.current = false;
    }, 180);
  };

  const onScroll = () => {
    const el = ref.current;
    if (!el || !byUser.current) return;
    const cents = Math.min(RULER_MAX, Math.max(RULER_MIN, Math.round(el.scrollLeft / RULER_STEP) + RULER_MIN));
    if (value === null || Math.round(value) !== cents) onPick(cents);
    touch(); // still moving (also covers the snap after a drag)
  };

  const items = [];
  for (let c = RULER_MIN; c <= RULER_MAX; c += 1) {
    items.push(
      <button
        key={c}
        type="button"
        tabIndex={-1}
        className={cx("price-ruler-item", c === shown && "is-on", c % 5 === 0 && "is-major")}
        onClick={() => {
          if (drag.current?.moved) return; // the end of a drag is not a tap
          onPick(c);
        }}
      >
        <i aria-hidden="true" />
        <span>{c}</span>
      </button>,
    );
  }

  return (
    <div className="price-ruler-wrap">
      <div
        ref={ref}
        className="price-ruler scrollbar"
        role="slider"
        tabIndex={0}
        aria-label="Limit price in cents"
        aria-valuemin={RULER_MIN}
        aria-valuemax={RULER_MAX}
        aria-valuenow={shown}
        onScroll={onScroll}
        onTouchStart={touch}
        onWheel={(event) => {
          // a mouse wheel scrolls up and down: turn it into left and right
          const el = ref.current;
          if (!el) return;
          touch();
          el.scrollLeft += Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
            event.preventDefault();
            onPick(Math.min(RULER_MAX, Math.max(RULER_MIN, shown + (event.key === "ArrowRight" ? 1 : -1))));
          }
        }}
        onPointerDown={(event) => {
          if (event.pointerType !== "mouse" || !ref.current) return;
          drag.current = { x: event.clientX, left: ref.current.scrollLeft, moved: false };
        }}
        onPointerMove={(event) => {
          const d = drag.current;
          const el = ref.current;
          if (!d || !el || event.buttons !== 1) return;
          const dx = event.clientX - d.x;
          if (!d.moved && Math.abs(dx) < 4) return;
          d.moved = true;
          touch();
          el.style.scrollSnapType = "none"; // free while dragging, snaps again on release
          el.scrollLeft = d.left - dx;
        }}
        onPointerUp={() => {
          const el = ref.current;
          if (el) el.style.scrollSnapType = "";
          window.setTimeout(() => {
            drag.current = null;
          }, 0);
        }}
        onPointerLeave={() => {
          const el = ref.current;
          if (el) el.style.scrollSnapType = "";
          drag.current = null;
        }}
      >
        {items}
      </div>
      <span className="price-ruler-marker" aria-hidden="true" />
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
            <PriceRuler
              value={valid ? price : null}
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
