import type { ApiResponse } from "../types";

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
    msg?: string,
  ) {
    super(msg ?? code);
    this.name = "ApiError";
  }
}

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    throw new ApiError("NETWORK_ERROR", 0);
  }

  let body: ApiResponse<T> | null = null;
  try {
    body = (await res.json()) as ApiResponse<T>;
  } catch {
  }

  if (!res.ok) {
    const code = body?.code ?? (res.status >= 500 ? "SERVER_ERROR" : "INVALID_PARAMETER");
    const message = (body?.data as { message?: unknown } | null | undefined)?.message;
    throw new ApiError(code, res.status, typeof message === "string" ? message : undefined);
  }

  if (!body || body.data === null || body.data === undefined) {
    return undefined as T;
  }
  return body.data;
}
