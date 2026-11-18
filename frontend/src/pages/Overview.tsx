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
import { useLiveRefresh } from "../hooks/useLiveRefresh";

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

  return (
    <div className="ov-grid">
      <RoundClockCard className="ov-top-left" />
      <BtcTapeCard orders={rows} className="ov-top-right" />

      <AccountCard className="ov-b-a" />
      <StageChecksCard orders={rows} className="ov-b-b" />
      <PositionsCard orders={rows} className="ov-b-c" />

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
  );
}
