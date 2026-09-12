"use client";

import { createContext, useContext, useState, type ReactNode } from "react";
import { matchesRegion } from "@/lib/region-color";

type RegionScopeValue = {
  selectedRegion: string | null;
  setSelectedRegion: (region: string | null) => void;
};

const RegionScopeContext = createContext<RegionScopeValue | null>(null);

/**
 * 選択中の地域をカレンダーと出演一覧で共有する（FR-10）。
 *
 * ページ全体は Server Component のまま。state はここにだけ置き、
 * URL には載せない（ADR-0022 / T-04）。
 */
export function RegionScope({ children }: { children: ReactNode }) {
  const [selectedRegion, setSelectedRegion] = useState<string | null>(null);
  return (
    <RegionScopeContext.Provider value={{ selectedRegion, setSelectedRegion }}>
      {children}
    </RegionScopeContext.Provider>
  );
}

export function useRegionScope(): RegionScopeValue {
  const value = useContext(RegionScopeContext);
  if (value === null) {
    throw new Error("useRegionScope must be used within RegionScope");
  }
  return value;
}

/**
 * 地域が絞り込みと一致しないときは出さない。
 *
 * 1 件・日付セクション・一覧全体で使う。children はサーバで描いたカード。
 * クライアントから中身は読めないので、包んで fallback を返す。
 */
export function RegionFiltered({
  regions,
  fallback = null,
  children,
}: {
  regions: string[];
  fallback?: ReactNode;
  children: ReactNode;
}): ReactNode {
  const { selectedRegion } = useRegionScope();
  if (regions.some((region) => matchesRegion(region, selectedRegion))) {
    return children;
  }
  return fallback;
}
