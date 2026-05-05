import { errorMessage } from "../lib/errorMessages";
export class ApiError extends Error {
    code;
    status;
    constructor(code, status, msg) {
        super(msg ?? errorMessage(code));
        this.code = code;
        this.status = status;
        this.name = "ApiError";
    }
}
export async function apiFetch(path, init) {
    let res;
    try {
        res = await fetch(path, {
            ...init,
            headers: {
                "Content-Type": "application/json",
                ...(init?.headers ?? {}),
            },
        });
    }
    catch {
        throw new ApiError("NETWORK_ERROR", 0);
    }
    let body = null;
    try {
        body = (await res.json());
    }
    catch {
        // empty / non-JSON
    }
    if (!res.ok) {
        const code = body?.code ?? (res.status >= 500 ? "SERVER_ERROR" : "INVALID_PARAMETER");
        throw new ApiError(code, res.status);
    }
    if (!body || body.data === null || body.data === undefined) {
        return undefined;
    }
    return body.data;
}
