import { useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, isNum } from "../../lib/format";
import { TAP_STAGE } from "../../lib/stages";
import { Modal } from "../Modal";
import { PriceSlider, parseCents } from "./PriceSlider";

/** 0.8 -> "80", 0.855 -> "85.5" (a limit is shown and typed in cents) */
function centsText(price: number): string {
  return String(Math.round(price * 1000) / 10);
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

  const parsed = parseCents(draft);
  const valid = parsed !== null;
  const price = parsed ?? 0;
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
            <PriceSlider
              draft={draft}
              onDraft={setDraft}
              mark={isNum(bid) ? bid * 100 : null}
              markLabel="bid"
              fallback={isNum(bid) ? bid * 100 : 50}
              onEnter={() => void save()}
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
