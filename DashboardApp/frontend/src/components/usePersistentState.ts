import { useState, useEffect } from "react";

export function usePersistedState<T>(key: string, initial: T): [T, (v: T) => void] {
  const [state, setState] = useState < T > (() => {
    const cached = window.localStorage.getItem(key);
    return cached ? JSON.parse(cached) as T : initial;
  });

  // 2 – save every change (debounce optional)
  useEffect(() => {
    window.localStorage.setItem(key, JSON.stringify(state));
  }, [key, state]);

  return [state, setState];
}
