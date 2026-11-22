import { useState } from "react";
import type { Side } from "../../api/types";
import { useBot } from "../../hooks/BotContext";
import { cx, fmtNum, fmtPx, fmtSignedUsd, isNum } from "../../lib/format";
import { TAP_STAGE } from "../../lib/stages";
import { Modal } from "../Modal";
import { PriceSlider, parseCents } from "./PriceSlider";

type Mode = "buy" | "sell";

/** One row of the sell list: an open position (a strategy stage or the one-tap shares) of the live market. */
type SellTarget = {
  key: string;
  side: Side;
  stage: number;
  /** shares held, null while the position does not exist yet (it waits for a buy limit to fill) */
  size: number | null;
  entry: number | null;
};

const BUY_PICKS = [40, 50, 60, 70, 80, 90, 95];
const SELL_PICKS = [50, 75, 90, 95, 99];
const AMOUNT_PICKS = [1, 2, 3, 5];

const targetKey = (side: Side, stage: number) => `${side}:${stage}`;
const stageLabel = (stage: number) => (stage === TAP_STAGE ? "One-tap" : `Stage ${stage}`);

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
 *  - Sell: you pick one of the active positions (a strategy stage or the one-tap shares of a side) and give THAT
 *    position its own limit price. A one-tap position that does not exist yet but will be bought by a buy limit is
 *    listed too, so its sell limit can be set before the buy fills.
 */
export function LimitOrderButton({ className }: { className?: string }) {
  const { snapshot, setLimit, cancelLimit, setBuyLimit, cancelBuyLimit } = useBot();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<Mode>("buy");
  const [side, setSide] = useState<Side>("UP");
  const [buyDraft, setBuyDraft] = useState("");
  const [selKey, setSelKey] = useState<string | null>(null);
  const [sellDrafts, setSellDrafts] = useState<Record<string, string>>({});
  const [usd, setUsd] = useState("10");
  const [busy, setBusy] = useState(false);

  if (!snapshot) return null;

  const market = snapshot.market;
  const marketId = market?.market_id ?? null;
  const buyLimits = (snapshot.buy_limits ?? []).filter((l) => l.market_id === marketId);
  const sellLimits = (snapshot.limits ?? []).filter((l) => l.market_id === marketId);
  const activeCount = buyLimits.length + sellLimits.length;
  const canSet = snapshot.bot.status === "RUNNING" && !snapshot.bot.kill_switch && !!market && market.remaining_seconds > 0;

  const bookOf = (s: Side) => (s === "UP" ? snapshot.books.up : snapshot.books.down);
  const sellLimitOf = (t: SellTarget) => sellLimits.find((l) => l.side === t.side && l.stage === t.stage);

  // the positions a sell limit can be set on: what is held now, plus the one-tap shares a buy limit is waiting to buy
  const targets: SellTarget[] = (snapshot.sellable ?? [])
    .filter((h) => h.market_id === marketId && h.size > 0)
    .map((h) => ({ key: targetKey(h.side, h.stage), side: h.side, stage: h.stage, size: h.size, entry: h.entry_price ?? null }));
  for (const l of buyLimits) {
    if (!targets.some((t) => t.side === l.side && t.stage === TAP_STAGE)) {
      targets.push({ key: targetKey(l.side, TAP_STAGE), side: l.side, stage: TAP_STAGE, size: null, entry: null });
    }
  }
  // strategy stages first, one-tap last (the same order as the positions card)
  targets.sort((a, b) => Number(a.stage === TAP_STAGE) - Number(b.stage === TAP_STAGE) || a.stage - b.stage || a.side.localeCompare(b.side));

  const buy = mode === "buy";
  const selected = targets.find((t) => t.key === selKey) ?? targets[0] ?? null;
  const selectedLimit = selected ? sellLimitOf(selected) : undefined;
  const viewSide: Side = buy ? side : (selected?.side ?? side);

  const book = bookOf(viewSide);
  const bid = book?.best_bid ?? null;
  const ask = book?.best_ask ?? null;
  const ref = buy ? ask : bid;

  const sellDraft = selected ? (sellDrafts[selected.key] ?? (selectedLimit ? centsText(selectedLimit.price) : "")) : "";
  const setSellDraft = (text: string) => {
    if (selected) setSellDrafts((prev) => ({ ...prev, [selected.key]: text }));
  };
  const draft = buy ? buyDraft : sellDraft;
  const setDraft = buy ? setBuyDraft : setSellDraft;
  const cents = parseCents(draft);
  const priceOk = cents !== null;

  const minUsd = snapshot.tap?.min_usdc ?? 1;
  const maxUsd = snapshot.tap?.max_usdc ?? 100;
  const amount = Number(usd);
  const amountOk = usd.trim() !== "" && Number.isFinite(amount) && amount >= minUsd && amount <= maxUsd;

  const alreadyThere = priceOk && isNum(ref) && (buy ? ref * 100 <= cents + 1e-9 : ref * 100 + 1e-9 >= cents);
  const valid = priceOk && (buy ? amountOk : selected !== null);
  const shares = buy && priceOk && amountOk ? amount / (cents / 100) : null;
  // profit of the buy if the side wins, at the limit price (a lower ask fills more shares, so it can only be more)
  const buyProfit = shares !== null ? shares - amount : null;

  // what the selected position gets at the typed limit
  const proceeds = !buy && priceOk && selected?.size != null ? (selected.size * cents) / 100 : null;
  const profit = proceeds !== null && selected?.entry != null && selected.size != null ? proceeds - selected.entry * selected.size : null;

  const openDialog = () => {
    // start from what is already set, so the dialog doubles as "change"
    const upBuy = buyLimits.find((l) => l.side === side);
    setBuyDraft(upBuy ? centsText(upBuy.price) : "");
    if (upBuy) setUsd(String(upBuy.amount));
    setSellDrafts({});
    setOpen(true);
  };

  const switchSide = (next: Side) => {
    setSide(next);
    const b = buyLimits.find((l) => l.side === next);
    setBuyDraft(b ? centsText(b.price) : "");
    if (b) setUsd(String(b.amount));
  };

  const save = async () => {
    if (!valid || busy || cents === null) return;
    setBusy(true);
    const result = buy
      ? await setBuyLimit(side, cents / 100, amount)
      : selected
        ? await setLimit(selected.side, selected.stage, cents / 100)
        : { ok: false };
    setBusy(false);
    // a buy limit is one thing to set, so the dialog closes; a sell limit stays open to give the next position its own
    if (result.ok && buy) setOpen(false);
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
      // only the warning that matters stays; the plain "the bot buys when the ask falls to..." line is gone
      return alreadyThere && isNum(ref) ? `The ask (${centsText(ref)}¢) is already at or below this limit: it buys right away.` : null;
    }
    if (selected?.size == null && selected) return "Nothing is held yet. This limit applies to the shares your buy limit buys.";
    if (alreadyThere && isNum(ref)) return `The bid (${centsText(ref)}¢) is already at or above this limit: the shares are sold right away.`;
    return isNum(ref)
      ? `The bot sells when the best bid reaches ${priceOk ? centsText(cents) : "this price"}¢ or higher. Bid now: ${centsText(ref)}¢.`
      : "The bot sells when the best bid reaches this price or higher. There is no bid right now.";
  })();

  const subtitle = buy
    ? isNum(bid) || isNum(ask)
      ? `${side} · bid ${isNum(bid) ? `${centsText(bid)}¢` : "—"} · ask ${isNum(ask) ? `${centsText(ask)}¢` : "—"}`
      : "No order book right now"
    : targets.length > 0
      ? "Pick a position, then set its own limit"
      : "No active position";

  const saveLabel = busy
    ? "Saving…"
    : buy
      ? "Set buy limit"
      : selected
        ? `${selectedLimit ? "Update" : "Set"} ${selected.side} sell limit`
        : "Set sell limit";

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
        subtitle={subtitle}
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
              {saveLabel}
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
            {buy ? (
              <div className="limit-seg" role="group" aria-label="UP or DOWN">
                {(["UP", "DOWN"] as const).map((s) => (
                  <button key={s} type="button" className={cx(s === "UP" ? "is-up" : "is-down", side === s && "is-on")} onClick={() => switchSide(s)}>
                    {s}
                  </button>
                ))}
              </div>
            ) : null}
          </div>

          {!buy ? (
            <div>
              <div className="tap-set-title">
                Active positions
                {targets.length > 0 ? <span className="limit-pos-count">{targets.length} open</span> : null}
              </div>
              {targets.length === 0 ? (
                <p className="tap-set-sub">
                  No open position to sell right now. Buy first, or set a buy limit: its shares show up here once it fills.
                </p>
              ) : (
                <div className="limit-pos-list" role="radiogroup" aria-label="Active positions">
                  {targets.map((t) => {
                    const on = selected?.key === t.key;
                    const lim = sellLimitOf(t);
                    const tBid = bookOf(t.side)?.best_bid ?? null;
                    const pnl = t.size != null && t.entry != null && isNum(tBid) ? (tBid - t.entry) * t.size : null;
                    return (
                      <button
                        key={t.key}
                        type="button"
                        role="radio"
                        aria-checked={on}
                        className={cx("limit-pos", on && "is-on")}
                        onClick={() => setSelKey(t.key)}
                      >
                        <span className="limit-pos-dot" aria-hidden="true" />
                        <span className="limit-pos-main">
                          <span className="limit-pos-top">
                            <span
                              className={cx(
                                "chip !px-2 !py-0.5 !text-[11.5px] font-bold",
                                t.side === "UP" ? "border-up/30 bg-up/12 text-up" : "border-down/30 bg-down/12 text-down",
                              )}
                            >
                              {t.side}
                            </span>
                            <span className="limit-pos-src">{stageLabel(t.stage)}</span>
                          </span>
                          <span className="limit-pos-detail">
                            {t.size != null ? `${fmtNum(t.size, 1)} shares at avg ${fmtPx(t.entry)}` : "Waiting for your buy limit to fill"}
                          </span>
                          <span className={cx("limit-pos-limit", lim && "is-set")}>
                            {lim ? `Sell at ${centsText(lim.price)}¢ or better` : "No limit yet"}
                          </span>
                        </span>
                        <span className="limit-pos-side">
                          <span className={cx("limit-pos-pnl", pnl === null ? "" : pnl >= 0 ? "text-up" : "text-down")}>
                            {pnl === null ? "—" : fmtSignedUsd(pnl)}
                          </span>
                          <span className="limit-pos-now">bid {fmtPx(tBid)}</span>
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          ) : null}

          {buy || selected ? (
            <div>
              <div className="tap-set-title">
                {buy
                  ? "Buy when the ask is this low or lower (¢)"
                  : `Sell ${selected?.side} (${stageLabel(selected?.stage ?? TAP_STAGE)}) at this price or better (¢)`}
              </div>
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
                extra={
                  buy && buyProfit !== null ? (
                    <>
                      <b>{fmtSignedUsd(buyProfit)}</b>
                      <small>profit</small>
                    </>
                  ) : null
                }
              />
              {hint ? <p className="tap-set-sub">{hint}</p> : null}
            </div>
          ) : null}

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
              {usd.trim() !== "" && !amountOk ? (
                <p className="tap-set-sub">{`Enter an amount from $${minUsd} to $${maxUsd}.`}</p>
              ) : null}
            </div>
          ) : proceeds !== null && selected ? (
            <div className="limit-sum">
              <div>
                <span>Sells</span>
                <b>
                  {fmtNum(selected.size ?? 0, 1)} {selected.side} shares
                </b>
              </div>
              <div>
                <span>You get at {centsText(cents ?? 0)}¢</span>
                <b>{usdText(proceeds)}</b>
              </div>
              {profit !== null ? (
                <div>
                  <span>Profit vs entry ({fmtPx(selected.entry)})</span>
                  <b className={profit >= 0 ? "text-up" : "text-down"}>{fmtSignedUsd(profit)}</b>
                </div>
              ) : null}
            </div>
          ) : null}

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
                    <b className="text-down">Sell {l.side}</b> at {centsText(l.price)}¢ or higher · {stageLabel(l.stage)}
                  </span>
                  <button type="button" onClick={() => void remove(() => cancelLimit(l.side, l.stage))}>
                    Remove
                  </button>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </Modal>
    </>
  );
}
