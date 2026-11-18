import type { ComponentType } from "react";
import {
  IconAnalytics,
  IconLogs,
  IconOverview,
  IconSettings,
  IconStrategy,
  IconTrading,
} from "../components/Icons";

export type Page = "overview" | "strategy" | "trading" | "analytics" | "settings" | "logs";

export interface NavItem {
  id: Page;
  label: string;
  icon: ComponentType<{ size?: number; className?: string }>;
}

export const NAV: NavItem[] = [
  { id: "overview", label: "Overview", icon: IconOverview },
  { id: "strategy", label: "Strategy monitor", icon: IconStrategy },
  { id: "trading", label: "Trading", icon: IconTrading },
  { id: "analytics", label: "Analytics", icon: IconAnalytics },
  { id: "settings", label: "Settings", icon: IconSettings },
  { id: "logs", label: "Logs", icon: IconLogs },
];

export const PAGE_META: Record<Page, { title: string; subtitle: string }> = {
  overview: {
    title: "Overview",
    subtitle: "Bitcoin up or down, 5 minutes",
  },
  strategy: {
    title: "Strategy monitor",
    subtitle: "Stage windows, entry rules and exits",
  },
  trading: {
    title: "Trading",
    subtitle: "Orders, fills, positions and execution quality",
  },
  analytics: {
    title: "Analytics",
    subtitle: "Profit, win rate and volume by mode",
  },
  settings: {
    title: "Settings",
    subtitle: "Strategy, risk and simulation parameters",
  },
  logs: {
    title: "Logs",
    subtitle: "Live event stream saved to the local database",
  },
};

export function pageFromHash(): Page {
  const hash = window.location.hash.replace("#", "").trim();
  return (NAV.find((item) => item.id === hash)?.id ?? "overview") as Page;
}
