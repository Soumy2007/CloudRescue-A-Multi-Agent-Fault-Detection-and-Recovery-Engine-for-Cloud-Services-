import { useCallback, useEffect, useState } from 'react'

/** Polls an async function; returns latest data, error and a manual refresh. */
export function usePolling<T>(fn: () => Promise<T>, intervalMs: number) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = useCallback(async () => {
    try { setData(await fn()); setError(null) } catch (e) { setError((e as Error).message) }
  }, [fn])
  useEffect(() => {
    refresh()
    const t = setInterval(refresh, intervalMs)
    return () => clearInterval(t)
  }, [refresh, intervalMs])
  return { data, error, refresh }
}
