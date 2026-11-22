import { useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, isNum } from "../../lib/format";
import { TAP_STAGE } from "../../lib/stages";
import { Modal } from "../Modal";
import { PriceSlider, parseCents } from "./PriceSlider";

type Mode = "buy" | "sell";

const BUY_PICKS = [20, 30, 40, 50, 60];
const SELL_PICKS = [50, 75, 90, 95, 99];
const AMOUNT_PICKS = [5, 10, 25, 50];

/** 0.8 -> "80", 0.855 -> "85.5" (a limit is shown and typed in cents) */
function centsText(price: number): string {
  return String(Math.round(price * 1000) / 10);
}

function usdText(amount: number): string {
  return `$${amount.toFixed(2).replace(/\.00$/, "")}`;
}

/**
 * "Limit" button in the header of the positions card: one dialog for a limit BUY and a limit SELL.
 *
 * Both are kept by the bot (code), never placed on the exchange, so nothing has to be cancelled there.
 *  - Buy: once the best ask is at or below the limit the bot makes ONE one-tap buy of the chosen amount that never
 *    pays more than the limit, then the limit is gone. It is manual like a one-tap buy: no stage rules, no strategy
 *    risk limits, no take profit / stop loss.
 *  - Sell: the limit sell of the one-tap shares of that side (it can be set before they exist, so it also picks up
 *    the shares a limit buy buys). Limits of a strategy stage stay on that stage's own row.
 */
export function LimitOrderButton({ className }: { className?: string }) {
  const { snapshot, setLimit, cancelLimit, setBuyLimit, cancelBuyLimit } = useBot();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<Mode>("buy");
  const [side, setSide] = useState<Side>("UP");
  const [buyDraft, setBuyDraft] = useState("");
  const [sellDraft, setSellDraft] = useState("");
  const [usd, setUsd] = useState("10");
  const [busy, setBusy] = useState(false);

  if (!snapshot) return null;

  const market = snapshot.market;
  const marketId = market?.market_id ?? null;
  const buyLimits = (snapshot.buy_limits ?? []).filter((l) => l.market_id === marketId);
  const sellLimits = (snapshot.limits ?? []).filter((l) => l.market_id === marketId);
  const activeCount = buyLimits.length + sellLimits.length;
  const canSet = snapshot.bot.status === "RUNNING" && !snapshot.bot.kill_switch && !!market && market.remaining_seconds > 0;

  const book = side === "UP" ? snapshot.books.up : snapshot.books.down;
  const bid = book?.best_bid ?? null;
  const ask = book?.best_ask ?? null;
  const buy = mode === "buy";
  const ref = buy ? ask : bid;

  const draft = buy ? buyDraft : sellDraft;
  const setDraft = buy ? setBuyDraft : setSellDraft;
  const cents = parseCents(draft);
  const priceOk = cents !== null;

  const minUsd = snapshot.tap?.min_usdc ?? 1;
  const maxUsd = snapshot.tap?.max_usdc ?? 100;
  const amount = Number(usd);
  const amountOk = usd.trim() !== "" && Number.isFinite(amount) && amount >= minUsd && amount <= maxUsd;

  const alreadyThere = priceOk && isNum(ref) && (buy ? ref * 100 <= cents + 1e-9 : ref * 100 + 1e-9 >= cents);
  const valid = priceOk && (!buy || amountOk);
  const shares = buy && priceOk && amountOk ? amount / (cents / 100) : null;

  const openDialog = () => {
    // start from what is already set, so the dialog doubles as "change"
    const upBuy = buyLimits.find((l) => l.side === side);
    const upSell = sellLimits.find((l) => l.side === side && l.stage === TAP_STAGE);
    setBuyDraft(upBuy ? centsText(upBuy.price) : "");
    setSellDraft(upSell ? centsText(upSell.price) : "");
    if (upBuy) setUsd(String(upBuy.amount));
    setOpen(true);
  };

  const switchSide = (next: Side) => {
    setSide(next);
    const b = buyLimits.find((l) => l.side === next);
    const s = sellLimits.find((l) => l.side === next && l.stage === TAP_STAGE);
    setBuyDraft(b ? centsText(b.price) : "");
    setSellDraft(s ? centsText(s.price) : "");
    if (b) setUsd(String(b.amount));
  };

  const save = async () => {
    if (!valid || busy || cents === null) return;
    setBusy(true);
    const result = buy ? await setBuyLimit(side, cents / 100, amount) : await setLimit(side, TAP_STAGE, cents / 100);
    setBusy(false);
    if (result.ok) setOpen(false);
  };

  const remove = async (action: () => Promise<{ ok: boolean }>) => {
    if (busy) return;
    setBusy(true);
    await action();
    setBusy(false);
  };

  const hint = (() => {
    if (draft.trim() !== "" && !priceOk) return "Enter a price from 1 to 99.";
    if (buy) {
      if (alreadyThere && isNum(ref)) return `The ask (${centsText(ref)}¢) is already at or below this limit: it buys right away.`;
      return isNum(ref)
        ? `The bot buys when the best ask falls to ${priceOk ? centsText(cents) : "this price"}¢ or lower. Ask now: ${centsText(ref)}¢.`
        : "The bot buys when the best ask falls to this price or lower. There is no ask right now.";
    }
    if (alreadyThere && isNum(ref)) return `The bid (${centsText(ref)}¢) is already at or above this limit: the shares are sold right away.`;
    return isNum(ref)
      ? `The bot sells when the best bid reaches ${priceOk ? centsText(cents) : "this price"}¢ or higher. Bid now: ${centsText(ref)}¢.`
      : "The bot sells when the best bid reaches this price or higher. There is no bid right now.";
  })();

  return (
    <>
      <button
        type="button"
        onClick={openDialog}
        disabled={!canSet && activeCount === 0}
        title="Set a limit buy or a limit sell for this round"
        className={cx("btn limit-btn limit-btn-row !px-2.5 !py-1 !text-[12px]", activeCount > 0 && "is-on", className)}
      >
        <span>Limit buy/sell</span>
        {activeCount > 0 ? <b>{activeCount}</b> : null}
      </button>

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        eyebrow="Limit order"
        title={buy ? "Buy limit" : "Sell limit"}
        subtitle={
          isNum(bid) || isNum(ask)
            ? `${side} · bid ${isNum(bid) ? `${centsText(bid)}¢` : "—"} · ask ${isNum(ask) ? `${centsText(ask)}¢` : "—"}`
            : "No order book right now"
        }
        maxWidth="max-w-md"
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button
              type="button"
              className={cx("btn", buy ? "btn-primary" : "btn-danger")}
              onClick={save}
              disabled={!valid || busy || !canSet}
            >
              {busy ? "Saving…" : buy ? "Set buy limit" : "Set sell limit"}
            </button>
          </>
        }
      >
        <div className="tap-set">
          <div className="grid gap-2">
            <div className="limit-seg" role="group" aria-label="Buy or sell">
              <button type="button" className={cx("is-buy", buy && "is-on")} onClick={() => setMode("buy")}>
                Buy
              </button>
              <button type="button" className={cx("is-sell", !buy && "is-on")} onClick={() => setMode("sell")}>
                Sell
              </button>
            </div>
            <div className="limit-seg" role="group" aria-label="UP or DOWN">
              {(["UP", "DOWN"] as const).map((s) => (
                <button key={s} type="button" className={cx(side === s && "is-on")} onClick={() => switchSide(s)}>
                  {s}
                </button>
              ))}
            </div>
          </div>

          <div>
            <div className="tap-set-title">{buy ? "Buy when the ask is this low or lower (¢)" : "Sell at this price or better (¢)"}</div>
            <PriceSlider
              draft={draft}
              onDraft={setDraft}
              mark={isNum(ref) ? ref * 100 : null}
              markLabel={buy ? "ask" : "bid"}
              fallback={isNum(ref) ? ref * 100 : 50}
              picks={buy ? BUY_PICKS : SELL_PICKS}
              tone={buy ? "up" : "down"}
              fill={buy ? "left" : "right"}
              onEnter={() => void save()}
            />
            <p className="tap-set-sub">{hint}</p>
          </div>

          {buy ? (
            <div>
              <div className="tap-set-title">Amount to spend</div>
              <div className="tap-set-fields">
                <label className="tap-field">
                  <span>$</span>
                  <input
                    inputMode="decimal"
                    value={usd}
                    aria-label="Amount in USDC"
                    onChange={(event) => setUsd(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") void save();
                    }}
                  />
                </label>
              </div>
              <div className="price-slider-quick">
                {AMOUNT_PICKS.map((value) => (
                  <button
                    key={value}
                    type="button"
                    className={cx("chip", amount === value && "is-on")}
                    onClick={() => setUsd(String(value))}
                  >
                    ${value}
                  </button>
                ))}
              </div>
              <p className="tap-set-sub">
                {usd.trim() !== "" && !amountOk
                  ? `Enter an amount from $${minUsd} to $${maxUsd}.`
                  : shares !== null
                    ? `About ${shares.toFixed(1)} shares at ${centsText(cents ?? 0)}¢ (more if the ask is lower). Pays $${shares.toFixed(2)} if ${side} wins.`
                    : "Pick a price to see how many shares that buys."}
              </p>
            </div>
          ) : (
            <p className="tap-set-sub">
              Sells your one-tap {side} shares, including the ones a buy limit buys later. A strategy stage keeps its own
              Limit button on its row.
            </p>
          )}

          {activeCount > 0 ? (
            <div>
              <div className="tap-set-title">Active limits</div>
              {buyLimits.map((l) => (
                <div key={`b-${l.side}`} className="limit-active">
                  <span>
                    <b className="text-up">Buy {l.side}</b> at {centsText(l.price)}¢ or lower · {usdText(l.amount)}
                  </span>
                  <button type="button" onClick={() => void remove(() => cancelBuyLimit(l.side))}>
                    Remove
                  </button>
                </div>
              ))}
              {sellLimits.map((l) => (
                <div key={`s-${l.side}-${l.stage}`} className="limit-active">
                  <span>
                    <b className="text-down">Sell {l.side}</b> at {centsText(l.price)}¢ or higher ·{" "}
                    {l.stage === TAP_STAGE ? "one-tap" : `stage ${l.stage}`}
                  </span>
                  <button type="button" onClick={() => void remove(() => cancelLimit(l.side, l.stage))}>
                    Remove
                  </button>
                </div>
              ))}
            </div>
          ) : null}

          <p className="tap-set-sub">
            Limits are kept by the bot, not placed on the exchange, and end with this round. A limit buy buys once and
            never pays more than its price.
          </p>
        </div>
      </Modal>
    </>
  );
}
