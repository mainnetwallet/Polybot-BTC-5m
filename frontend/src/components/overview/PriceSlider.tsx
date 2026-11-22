import { cx } from "../../lib/format";

const SLIDER_MIN = 1;
const SLIDER_MAX = 99;
const SLIDER_THUMB = 22; // px: must match .price-slider input's thumb in index.css
const DEFAULT_PICKS = [50, 75, 90, 95, 99];

/** where a price sits on the track (px offset is the thumb's half width, so the marker lines up with the thumb) */
function trackPos(cents: number): string {
  const pct = ((Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, cents)) - SLIDER_MIN) / (SLIDER_MAX - SLIDER_MIN)) * 100;
  return `calc(${pct}% + ${(SLIDER_THUMB / 2) * (1 - pct / 50)}px)`;
}

/** the typed text as a price in cents (1-99, decimals allowed), null when empty or invalid */
export function parseCents(draft: string): number | null {
  const value = Number(draft);
  return draft.trim() !== "" && Number.isFinite(value) && value >= SLIDER_MIN && value <= SLIDER_MAX ? value : null;
}

/**
 * A limit price in one drag: a slider over the whole 1-99 range, a - / + pair for single cents, and a few quick
 * picks. The big number is the field itself: type in it (decimals like 85.5 are kept as typed) and the slider
 * follows it to the nearest cent. The white mark on the track is the best bid / ask, so you can see how far the
 * limit is from the market.
 */
export function PriceSlider({
  draft,
  onDraft,
  mark,
  markLabel = "bid",
  fallback,
  picks = DEFAULT_PICKS,
  tone = "up",
  fill = "left",
  onEnter,
}: {
  /** the text in the price field (cents) */
  draft: string;
  onDraft: (text: string) => void;
  /** the best bid / ask in cents, null when there is none */
  mark: number | null;
  markLabel?: string;
  /** where the thumb sits while the field is empty (the market price, else 50) */
  fallback: number;
  picks?: number[];
  /** colour of the thumb and the filled part: up = green, down = red */
  tone?: "up" | "down";
  /** which side of the thumb is filled: "left" for "this price or lower", "right" for "this price or higher" */
  fill?: "left" | "right";
  onEnter?: () => void;
}) {
  const clamp = (cents: number) => Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, Math.round(cents)));
  const value = parseCents(draft);
  const shown = clamp(value ?? fallback);
  const markShown = mark === null ? null : clamp(mark);
  const pick = (cents: number) => onDraft(String(clamp(cents)));

  return (
    <div className={cx("price-slider", tone === "down" && "is-down")}>
      <div className="price-slider-step">
        <button type="button" className="btn btn-ghost" aria-label="One cent less" onClick={() => pick(shown - 1)}>
          −
        </button>
        <label className="price-slider-value">
          <input
            inputMode="decimal"
            value={draft}
            aria-label="Limit price in cents"
            placeholder={String(shown)}
            onChange={(event) => onDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") onEnter?.();
            }}
          />
          <small>¢</small>
        </label>
        <button type="button" className="btn btn-ghost" aria-label="One cent more" onClick={() => pick(shown + 1)}>
          +
        </button>
      </div>

      <div className="price-slider-track">
        <span className="price-slider-bar" aria-hidden="true" />
        <span
          className="price-slider-fill"
          aria-hidden="true"
          style={fill === "right" ? { left: trackPos(shown), right: 0 } : { width: trackPos(shown) }}
        />
        {markShown !== null ? (
          <span className="price-slider-bid" aria-hidden="true" style={{ left: trackPos(markShown) }} />
        ) : null}
        <input
          type="range"
          min={SLIDER_MIN}
          max={SLIDER_MAX}
          step={1}
          value={shown}
          aria-label="Limit price in cents"
          onChange={(event) => pick(Number(event.target.value))}
        />
      </div>

      <div className="price-slider-ticks" aria-hidden="true">
        {[SLIDER_MIN, 25, 50, 75, SLIDER_MAX]
          .filter((tick) => markShown === null || Math.abs(tick - markShown) > 6) /* the mark's label needs the room */
          .map((tick) => (
            <span key={tick} style={{ left: trackPos(tick) }}>
              {tick}
            </span>
          ))}
        {markShown !== null ? (
          <span className="is-bid" style={{ left: trackPos(markShown) }}>
            {markLabel}
          </span>
        ) : null}
      </div>

      <div className="price-slider-quick">
        {picks.map((cents) => (
          <button key={cents} type="button" className={cx("chip", value === cents && "is-on")} onClick={() => pick(cents)}>
            {cents}¢
          </button>
        ))}
      </div>
    </div>
  );
}
