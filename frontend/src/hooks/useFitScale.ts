import { useEffect, useState, type RefObject } from "react";

/**
 * The Overview is drawn as one screen, like the design: two rows of cards that fill the window. It is laid
 * out on a virtual canvas of about REF_W x REF_H and scaled to the real window, so it looks the same on a
 * 1366 px laptop, a 1920 px monitor or with browser zoom on. Below MIN_VIEW_W (phones, narrow windows) there
 * is no scaling and the cards stack.
 */
export const REF_W = 1440;
export const REF_H = 800;
export const MIN_SCALE = 0.7;
export const MAX_SCALE = 1.3;
export const MIN_VIEW_W = 900;
/** a very short window scrolls instead of squeezing the cards below this canvas height */
export const MIN_CANVAS_H = 780;

export interface FitState {
  /** false = stacked layout (narrow window), no scaling */
  active: boolean;
  scale: number;
  /** size of the virtual canvas in its own px (window size divided by the scale) */
  width: number;
  height: number;
}

const OFF: FitState = { active: false, scale: 1, width: 0, height: 0 };

/** `host` sits inside <main> (the scroll area); the padding of its parent is the window margin. */
export function useFitScale(host: RefObject<HTMLElement | null>): FitState {
  const [state, setState] = useState<FitState>(OFF);

  useEffect(() => {
    const node = host.current;
    const main = node?.closest("main");
    const frame = node?.parentElement;
    if (!node || !main || !frame) return;

    const measure = () => {
      const pad = getComputedStyle(frame);
      const w = frame.clientWidth - parseFloat(pad.paddingLeft) - parseFloat(pad.paddingRight);
      const h = main.clientHeight - parseFloat(pad.paddingTop) - parseFloat(pad.paddingBottom);
      if (!(w >= MIN_VIEW_W) || !(h > 0)) {
        setState((prev) => (prev.active ? OFF : prev));
        return;
      }
      const scale = Math.round(Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.min(w / REF_W, h / REF_H))) * 1000) / 1000;
      const next: FitState = { active: true, scale, width: Math.round(w / scale), height: Math.max(MIN_CANVAS_H, Math.round(h / scale)) };
      setState((prev) =>
        prev.active && prev.scale === next.scale && prev.width === next.width && prev.height === next.height ? prev : next,
      );
    };

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(main);
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, [host]);

  return state;
}
