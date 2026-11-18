import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { AccountCard } from "../components/overview/AccountCard";
import { BtcTapeCard } from "../components/overview/BtcTapeCard";
import { PositionsCard } from "../components/overview/PositionsCard";
import { RiskFeedCard } from "../components/overview/RiskFeedCard";
import { RoundClockCard } from "../components/overview/RoundClockCard";
import { StageChecksCard } from "../components/overview/StageChecksCard";
import { Card } from "../components/Card";
import { LoadingPanel } from "../components/LoadingPanel";
import { OrdersTable } from "../components/Tables";
import { useBot } from "../hooks/BotContext";
import { useFitScale } from "../hooks/useFitScale";
import { useLiveRefresh } from "../hooks/useLiveRefresh";
import { cx } from "../lib/format";

export function Overview() {
  const { snapshot, dataVersion } = useBot();
  const mode = snapshot?.bot.mode;
  const orders = useLiveRefresh(
    () => (mode ? api.orders(mode, 60) : Promise.resolve([])),
    [mode],
    dataVersion,
    mode !== undefined,
  );
  const rows = orders.data ?? [];

  const host = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLDivElement>(null);
  const fit = useFitScale(host);

  // a transform does not change the layout size, so the host takes the scaled height of the canvas
  const [canvasHeight, setCanvasHeight] = useState(0);
  useEffect(() => {
    const node = canvas.current;
    if (!node || !fit.active) return;
    const observer = new ResizeObserver(() => setCanvasHeight(node.offsetHeight));
    observer.observe(node);
    setCanvasHeight(node.offsetHeight);
    return () => observer.disconnect();
  }, [fit.active]);

  return (
    <div ref={host} style={fit.active ? { height: canvasHeight * fit.scale } : undefined}>
      <div
        ref={canvas}
        style={
          fit.active
            ? { width: fit.width, transform: `scale(${fit.scale})`, transformOrigin: "0 0" }
            : undefined
        }
      >
        {/* the screen of the design: six cards in two rows that fill the window */}
        <div className={cx("ov-grid", fit.active && "ov-screen")} style={fit.active ? { height: fit.height } : undefined}>
          <RoundClockCard className="ov-top-left" />
          <BtcTapeCard orders={rows} className="ov-top-right" />

          <AccountCard className="ov-b-a" />
          <StageChecksCard orders={rows} className="ov-b-b" />
          <PositionsCard orders={rows} className="ov-b-c" />
        </div>

        {/* below the first screen */}
        <div className="ov-grid ov-extra">
          <Card
            title="Recent orders"
            className="ov-orders overflow-hidden"
            right={<span>{snapshot ? `${snapshot.open_orders.length} open · ${mode ?? "—"}` : "—"}</span>}
            bodyClassName="-mx-5 -mb-2"
          >
            {orders.data ? (
              <OrdersTable orders={rows.slice(0, 8)} emptyHint="Triggers that qualify create order records." />
            ) : (
              <LoadingPanel className="m-4" />
            )}
          </Card>
          <RiskFeedCard className="ov-risk" />
        </div>
      </div>
    </div>
  );
}
